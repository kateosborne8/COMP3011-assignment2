package comp3011;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Hand-off point between the frame workers and the displayer.
 *
 * <p>
 * Workers finish frames in any order, but the displayer must see them in frame order. Results are stored by frame
 * number, and a worker may only work on frame {@code k} while {@code k < nextToDisplay + window}. That sliding window
 * bounds the number of frames in flight, and because the frame the displayer needs is always inside the window, the
 * worker holding it can always proceed (so no deadlock).
 * </p>
 *
 * <p>
 * Thread safety: every field is guarded by {@link #lock}. Neither {@link #pollNext()} nor {@link #deliver} ever
 * waits, so the JavaFX thread is never blocked by this class. Only {@link #awaitSlot} waits.
 * </p>
 *
 * @param <T> the type of a finished frame
 */
public class OrderedBuffer<T> {
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition windowMoved = lock.newCondition();

    private final int window;

    // Everything below is guarded by 'lock'.
    private final Map<Integer, T> ready = new HashMap<>();
    private int nextToDisplay;
    private long generation;
    private boolean closed;

    public OrderedBuffer(int window, int firstFrame) {
        if (window < 1) {
            throw new IllegalArgumentException("window must be at least 1");
        }
        this.window = window;
        this.nextToDisplay = firstFrame;
    }

    /**
     * The current generation. A worker reads this when it takes a frame from the input queue and passes it back to
     * {@link #awaitSlot} and {@link #deliver}, so that work started before a seek can be recognised as stale.
     */
    public long generation() {
        lock.lock();
        try {
            return generation;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Called by a worker BEFORE processing frame {@code k}. Blocks until the frame is inside the window.
     *
     * @return true if the worker should process the frame; false if the work was cancelled (seek, stop or shutdown)
     *         and the frame should be dropped
     */
    public boolean awaitSlot(int k, long gen) throws InterruptedException {
        lock.lock();
        try {
            while (k >= nextToDisplay + window && gen == generation && !closed) {
                windowMoved.await();
            }
            return gen == generation && !closed;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Called by a worker AFTER processing. Never blocks: awaitSlot already guaranteed the frame fits in the window.
     * A result from an old generation is silently discarded.
     */
    public void deliver(int k, long gen, T result) {
        lock.lock();
        try {
            if (gen == generation && !closed) {
                ready.put(k, result);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Called by the displayer (the JavaFX thread). Never blocks: returns empty if the next frame isn't ready yet, and
     * the caller simply tries again on its next tick.
     */
    public Optional<T> pollNext() {
        lock.lock();
        try {
            T result = ready.remove(nextToDisplay);
            if (result == null) {
                return Optional.empty();
            }
            nextToDisplay++;
            windowMoved.signalAll(); // the window moved, so waiting workers must re-check
            return Optional.of(result);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Called by the controller on seek or stop. Discards stored frames, invalidates in-flight work and wakes any
     * sleeping workers so they notice and drop their frames.
     */
    public void reset(int newFirstFrame) {
        lock.lock();
        try {
            generation++;
            ready.clear();
            nextToDisplay = newFirstFrame;
            windowMoved.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** Called on shutdown. Permanently releases every waiting worker. */
    public void close() {
        lock.lock();
        try {
            closed = true;
            ready.clear();
            windowMoved.signalAll();
        } finally {
            lock.unlock();
        }
    }
}
