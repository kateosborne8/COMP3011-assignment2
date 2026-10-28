package comp3011;

import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.bytedeco.javacv.Frame;

/**
 * The worker half of the playback pipeline: a bounded input queue ("InQ"), a pool of worker threads, and an
 * {@link OrderedBuffer} that hands finished frames to the displayer in frame order.
 *
 * <p>
 * One producer (the decoder thread) calls {@link #submit}. Each worker takes a {@link Job}, waits until its frame is
 * inside the buffer's window, transforms it, and delivers the result. The displayer (the JavaFX thread) only ever
 * calls {@link #poll}, which never blocks.
 * </p>
 *
 * <p>
 * Every worker owns its own {@link Transformer}, so transformers need no internal locking and may keep scratch
 * state. The producer stamps each job with the generation it was created in; stale jobs and results (from before a
 * {@link #reset}) are dropped by the buffer.
 * </p>
 *
 * @param <R> the type a transformer produces for each frame
 */
public final class FramePipeline<R> implements AutoCloseable {

    /** Per-worker processing step. Created on the worker thread and closed by it when the worker exits. */
    public interface Transformer<R> extends AutoCloseable {
        R transform(Job job) throws Exception;

        @Override
        void close();
    }

    /** A unit of work. Ordinary frames carry a cloned frame; end-of-stream and failure are in-band markers. */
    public record Job(
            int sequence,
            long generation,
            Frame frame,
            int frameNumber,
            long timestampUs,
            InfoVideo videoInfo,
            boolean endOfStream,
            Exception failure) {

        public static Job frame(int sequence, long generation, Frame frame, int frameNumber, long timestampUs,
                InfoVideo videoInfo) {
            return new Job(sequence, generation, frame, frameNumber, timestampUs, videoInfo, false, null);
        }

        public static Job end(int sequence, long generation) {
            return new Job(sequence, generation, null, 0, 0, null, true, null);
        }

        public static Job failed(int sequence, long generation, Exception failure) {
            return new Job(sequence, generation, null, 0, 0, null, false, failure);
        }
    }

    /** What the displayer receives: a finished frame, the end of the stream, or a failure. */
    public record Output<R>(R value, boolean endOfStream, Exception failure) {
        public boolean isFrame() {
            return value != null;
        }
    }

    private static final long SHUTDOWN_WAIT_MS = 2_000;

    private final OrderedBuffer<Output<R>> buffer;
    private final BlockingQueue<Job> inQ;
    private final ExecutorService workers;
    private final Supplier<Transformer<R>> transformerFactory;

    public FramePipeline(int workerCount, int window, int inputCapacity, Supplier<Transformer<R>> transformerFactory) {
        this.buffer = new OrderedBuffer<>(window, 0);
        this.inQ = new ArrayBlockingQueue<>(inputCapacity);
        this.transformerFactory = transformerFactory;
        this.workers = Executors.newFixedThreadPool(workerCount, new WorkerThreadFactory());
        for (int i = 0; i < workerCount; i++) {
            workers.execute(this::workerLoop);
        }
    }

    /** The generation a producer should stamp on the jobs it creates from now on. */
    public long generation() {
        return buffer.generation();
    }

    /** Called by the producer. Blocks while the input queue is full (backpressure). */
    public void submit(Job job) throws InterruptedException {
        inQ.put(job);
    }

    /** Called by the displayer. Never blocks. */
    public Optional<Output<R>> poll() {
        return buffer.pollNext();
    }

    /**
     * Discards everything in flight and restarts frame numbering at 0. The producer must be stopped first, and
     * should read {@link #generation()} again before it resumes.
     */
    public void reset() {
        buffer.reset(0);
        inQ.clear();
    }

    /** Stops the workers and waits briefly for them to exit. Safe to call more than once. */
    @Override
    public void close() {
        buffer.close();
        workers.shutdownNow();
        try {
            workers.awaitTermination(SHUTDOWN_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        inQ.clear();
    }

    private void workerLoop() {
        try (Transformer<R> transformer = transformerFactory.get()) {
            while (!Thread.currentThread().isInterrupted()) {
                Job job = inQ.take();
                if (!buffer.awaitSlot(job.sequence(), job.generation())) {
                    continue; // cancelled by seek, stop or shutdown
                }
                buffer.deliver(job.sequence(), job.generation(), run(transformer, job));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Output<R> run(Transformer<R> transformer, Job job) {
        if (job.endOfStream()) {
            return new Output<>(null, true, null);
        }
        if (job.failure() != null) {
            return new Output<>(null, false, job.failure());
        }
        try {
            return new Output<>(transformer.transform(job), false, null);
        } catch (Exception e) {
            return new Output<>(null, false, e);
        }
    }

    private static final class WorkerThreadFactory implements ThreadFactory {
        private final AtomicInteger count = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "frame-worker-" + count.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
