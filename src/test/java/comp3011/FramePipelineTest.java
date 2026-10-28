package comp3011;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Tests for {@link FramePipeline} using fake transformers, so no JavaFX or FFmpeg is needed.
 */
class FramePipelineTest {

    private static FramePipeline.Job job(int sequence, long generation) {
        return FramePipeline.Job.frame(sequence, generation, null, sequence, sequence * 1000L, null);
    }

    private static <R> FramePipeline.Output<R> awaitOutput(FramePipeline<R> pipeline) throws InterruptedException {
        while (true) {
            Optional<FramePipeline.Output<R>> output = pipeline.poll();
            if (output.isPresent()) {
                return output.get();
            }
            Thread.sleep(1);
        }
    }

    private static FramePipeline.Transformer<Integer> transformer(FramePipeline.Transformer<Integer> t) {
        return t;
    }

    @Test
    @Timeout(10)
    @DisplayName("frames come out in order although workers finish at random times")
    void outputIsInOrder() throws Exception {
        try (FramePipeline<Integer> pipeline = new FramePipeline<>(3, 3, 2, () -> new FramePipeline.Transformer<>() {
            public Integer transform(FramePipeline.Job job) throws Exception {
                Thread.sleep(ThreadLocalRandom.current().nextInt(5));
                return job.frameNumber();
            }

            public void close() {
            }
        })) {
            int count = 200;
            Thread producer = new Thread(() -> {
                try {
                    long gen = pipeline.generation();
                    for (int i = 0; i < count; i++) {
                        pipeline.submit(job(i, gen));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            producer.start();

            for (int i = 0; i < count; i++) {
                assertEquals(i, awaitOutput(pipeline).value());
            }
            producer.join();
        }
    }

    @Test
    @Timeout(10)
    @DisplayName("a transformer that throws is reported in order and does not hang the pipeline")
    void failureIsDelivered() throws Exception {
        try (FramePipeline<Integer> pipeline = new FramePipeline<>(3, 3, 2, () -> new FramePipeline.Transformer<>() {
            public Integer transform(FramePipeline.Job job) throws Exception {
                if (job.sequence() == 2) {
                    throw new IllegalStateException("boom");
                }
                return job.sequence();
            }

            public void close() {
            }
        })) {
            long gen = pipeline.generation();
            for (int i = 0; i < 3; i++) {
                pipeline.submit(job(i, gen));
            }
            assertEquals(0, awaitOutput(pipeline).value());
            assertEquals(1, awaitOutput(pipeline).value());
            FramePipeline.Output<Integer> failed = awaitOutput(pipeline);
            assertEquals("boom", failed.failure().getMessage());
        }
    }

    @Test
    @Timeout(10)
    @DisplayName("end of stream marker arrives after the last frame")
    void endMarkerFollowsFrames() throws Exception {
        try (FramePipeline<Integer> pipeline = new FramePipeline<>(3, 3, 2, () -> new FramePipeline.Transformer<>() {
            public Integer transform(FramePipeline.Job job) {
                return job.sequence();
            }

            public void close() {
            }
        })) {
            long gen = pipeline.generation();
            pipeline.submit(job(0, gen));
            pipeline.submit(FramePipeline.Job.end(1, gen));
            assertEquals(0, awaitOutput(pipeline).value());
            assertTrue(awaitOutput(pipeline).endOfStream());
        }
    }

    @Test
    @Timeout(10)
    @DisplayName("after reset, old work is dropped and new frames start again at 0")
    void resetDropsStaleWork() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (FramePipeline<String> pipeline = new FramePipeline<>(1, 3, 2, () -> new FramePipeline.Transformer<>() {
            public String transform(FramePipeline.Job job) throws Exception {
                if (job.generation() == 0) {
                    started.countDown();
                    release.await(); // hold the old-generation frame in flight across the reset
                }
                return "gen" + job.generation() + "-" + job.sequence();
            }

            public void close() {
            }
        })) {
            pipeline.submit(job(0, pipeline.generation()));
            assertTrue(started.await(5, TimeUnit.SECONDS));

            pipeline.reset();
            long newGen = pipeline.generation();
            release.countDown(); // the stale result must now be discarded
            pipeline.submit(job(0, newGen));

            assertEquals("gen" + newGen + "-0", awaitOutput(pipeline).value());
            assertTrue(pipeline.poll().isEmpty(), "the stale frame must not appear");
        }
    }

    @Test
    @Timeout(10)
    @DisplayName("close stops every worker thread, even ones waiting for the window")
    void closeStopsWorkers() throws Exception {
        List<Thread> workers = new ArrayList<>();
        FramePipeline<Integer> pipeline = new FramePipeline<>(3, 1, 4, () -> new FramePipeline.Transformer<>() {
            public Integer transform(FramePipeline.Job job) {
                synchronized (workers) {
                    if (!workers.contains(Thread.currentThread())) {
                        workers.add(Thread.currentThread());
                    }
                }
                return job.sequence();
            }

            public void close() {
            }
        });
        long gen = pipeline.generation();
        for (int i = 0; i < 4; i++) {
            pipeline.submit(job(i, gen)); // window is 1, so frames 1..3 block in awaitSlot until close
        }
        Thread.sleep(100);
        pipeline.close();

        synchronized (workers) {
            for (Thread worker : workers) {
                assertFalse(worker.isAlive(), worker.getName() + " should have exited");
            }
        }
    }
}
