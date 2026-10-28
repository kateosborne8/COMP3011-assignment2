package comp3011;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Tests for the ordering, windowing and cancellation rules of {@link OrderedBuffer}. No JavaFX or FFmpeg needed. The
 * timeouts turn a deadlock into a test failure rather than a hung build.
 */
class OrderedBufferTest {

    @Test
    @DisplayName("frames come out in order even when delivered out of order")
    void outOfOrderDeliveryIsReordered() throws Exception {
        OrderedBuffer<String> buffer = new OrderedBuffer<>(3, 0);
        long gen = buffer.generation();

        buffer.deliver(1, gen, "one");
        assertTrue(buffer.pollNext().isEmpty(), "frame 0 not delivered yet, so nothing may be shown");

        buffer.deliver(0, gen, "zero");
        assertEquals("zero", buffer.pollNext().orElseThrow());
        assertEquals("one", buffer.pollNext().orElseThrow());
    }

    @Test
    @Timeout(5)
    @DisplayName("a worker outside the window blocks until the displayer advances")
    void workerBlocksUntilWindowMoves() throws Exception {
        OrderedBuffer<String> buffer = new OrderedBuffer<>(3, 0);
        long gen = buffer.generation();
        CountDownLatch passed = new CountDownLatch(1);

        Thread worker = new Thread(() -> {
            try {
                if (buffer.awaitSlot(3, gen)) { // window is 0..2, so frame 3 must wait
                    passed.countDown();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        worker.start();

        assertFalse(passed.await(200, TimeUnit.MILLISECONDS), "frame 3 must not pass while next=0");

        buffer.deliver(0, gen, "zero");
        buffer.pollNext(); // next becomes 1, window is 1..3

        assertTrue(passed.await(2, TimeUnit.SECONDS), "frame 3 should be released once next=1");
        worker.join();
    }

    @Test
    @Timeout(5)
    @DisplayName("a slow frame does not deadlock the others")
    void slowFrameDoesNotDeadlock() throws Exception {
        OrderedBuffer<Integer> buffer = new OrderedBuffer<>(3, 10);
        long gen = buffer.generation();

        // Fast workers finish 11 and 12 while frame 10 is still being processed
        assertTrue(buffer.awaitSlot(11, gen));
        buffer.deliver(11, gen, 11);
        assertTrue(buffer.awaitSlot(12, gen));
        buffer.deliver(12, gen, 12);

        // The slow worker holding the awaited frame can still get in and deliver
        assertTrue(buffer.awaitSlot(10, gen));
        buffer.deliver(10, gen, 10);

        assertEquals(10, buffer.pollNext().orElseThrow());
        assertEquals(11, buffer.pollNext().orElseThrow());
        assertEquals(12, buffer.pollNext().orElseThrow());
    }

    @Test
    @Timeout(5)
    @DisplayName("reset wakes blocked workers, which are told to drop their frame")
    void resetCancelsWaitingWorkers() throws Exception {
        OrderedBuffer<String> buffer = new OrderedBuffer<>(3, 0);
        long gen = buffer.generation();
        AtomicBoolean shouldProcess = new AtomicBoolean(true);

        Thread worker = new Thread(() -> {
            try {
                shouldProcess.set(buffer.awaitSlot(50, gen));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        worker.start();
        Thread.sleep(100); // let it reach await()

        buffer.reset(100);
        worker.join(2000);

        assertFalse(worker.isAlive(), "reset must wake the sleeping worker");
        assertFalse(shouldProcess.get(), "the stale frame must be dropped");
    }

    @Test
    @DisplayName("a result from before a reset is discarded")
    void staleResultIsDiscarded() throws Exception {
        OrderedBuffer<String> buffer = new OrderedBuffer<>(3, 0);
        long oldGen = buffer.generation();

        buffer.reset(0); // seek happens while a worker is mid-processing
        buffer.deliver(0, oldGen, "stale");

        assertTrue(buffer.pollNext().isEmpty());
    }

    @Test
    @Timeout(5)
    @DisplayName("close releases waiting workers")
    void closeReleasesWorkers() throws Exception {
        OrderedBuffer<String> buffer = new OrderedBuffer<>(3, 0);
        long gen = buffer.generation();
        AtomicBoolean shouldProcess = new AtomicBoolean(true);

        Thread worker = new Thread(() -> {
            try {
                shouldProcess.set(buffer.awaitSlot(50, gen));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        worker.start();
        Thread.sleep(100);

        buffer.close();
        worker.join(2000);

        assertFalse(worker.isAlive());
        assertFalse(shouldProcess.get());
    }
}
