# COMP3011 Assignment 2 - Java Local Project

For more information on this code and how to get started, see https://learn.adelaide.edu.au/courses/30747/assignments/94505

# Multithreaded video player

The original player did everything on the JavaFX thread: decode a frame, run every effect on it, convert it and show
it. With lots of effects stacked the CPU work per frame was more than the time available, so playback fell behind and
the window stopped responding. Now the effects run on a pool of worker threads. Frames are still shown in order, on
the JavaFX thread, and a seek or replay gives exactly the same output as before.

## Build, test and run

```
mvn test
mvn javafx:run -Djavafx.args="-nsfwyvdjmbp path/to/video.mp4"
mvn package -Pfat-jar
```

Use `--help` to see the effects. They are applied in the order given and can be repeated. Once a second the console
prints the frame headroom, which is how long before it was due that a worker finished the frame. Negative means late.

## How it works

```
             InQ (bounded)                        OrderedBuffer
 DECODER  ─────────────────►  WORKER 1..N ─────►  ready frames  ─────►  JavaFX thread
 owns the grabber             own effect chain    next, window,         polls and shows
                                                  generation            frames when due

 The model (JavaFX thread) starts, seeks, stops and shuts down all of the above.
```

- **Decoder thread** is the only thread that touches the `FFmpegFrameGrabber`. It clones each frame and puts it on the
  bounded queue.
- **Worker threads** take a frame, run their own copy of the effect chain, convert it to an image and hand it to the
  `OrderedBuffer`.
- **JavaFX thread** polls the buffer for the next frame and shows it when its time comes. It never waits.

The main classes are `VideoPlayerModel` (coordinates everything and owns the playback clock), `FramePipeline` (queue
and worker pool) and `OrderedBuffer` (the hand-off between workers and the display).

## Shared resources and how they are protected

| Resource | Problem | Fix |
|---|---|---|
| The grabber | Not thread-safe, and seeking moves it | Only the decoder thread uses it. Seek and close stop and `join` the decoder first |
| The grabber's `Frame` | It is reused, so the decoder overwrites pixels a worker is still using | `frame.clone()` for each job |
| Effect scratch buffers | Workers would overwrite each other's data and nothing would warn us | Every worker has its own effect chain |
| Effect numbering | A `static` counter was unsynchronised and gave different seeds per worker | The index is passed in with `FrameEffect.create(occurrence)` |
| Buffer state (finished frames, next frame, generation, closed) | Lost updates, stale frames, workers waiting forever | One lock for all of it, and effects run outside the lock |
| Cancelling work on seek | Checking "is this still wanted" and then acting on it is a race | Each job is stamped with a generation when the decoder creates it, and `OrderedBuffer` checks it again in `awaitSlot` and `deliver` |
| Input queue | The decoder would run ahead and fill memory | Bounded queue, so the decoder waits when it is full |
| Audio | Decoder writes, JavaFX thread reads | `ConcurrentLinkedQueue`, and `audioOutputEnabled` is `volatile` |
| Playback clock, pause and seek state | Inconsistent timing | Only the JavaFX thread touches them |

## Why it is correct

**Order.** Workers finish in any order, but results are stored by sequence number and the display only asks for the
next one. If frame 7 finishes before 6 it waits in the buffer.

**No deadlock.** A worker can only process frame `k` if `k < next + WINDOW`, and jobs leave the queue in order. So the
frame the display needs is always in the buffer, held by a worker that is allowed to run, or still in the queue behind
workers that only hold earlier frames. Something can always make progress, and `pollNext` wakes the waiting workers when
`next` moves. The window check happens before processing so `deliver` never has to wait.

**The UI never blocks.** `pollNext` and `deliver` never wait. If the next frame is not ready the JavaFX thread tries
again on the next tick. Pausing works because it stops polling, so the window fills, then the workers wait, then the
queue fills, then the decoder blocks.

**Seek.** Stop the decoder, bump the generation and clear the buffer and queue, move the grabber, start a new decoder
with the new generation. Old frames are dropped wherever they are because their generation is out of date. Seeks all
run on the JavaFX thread so they can't overlap.

**Same output every time.** An effect's output only depends on the media name, the real frame number and its position
in the chain, and every worker builds the chain from the same list. It doesn't matter which worker gets a frame.

**Errors and the end of the video.** An exception on another thread can't be caught by the JavaFX thread, so it is
caught where it happens and passed through the queue and buffer as data, in order with the frames. The end of the
video works the same way, so the last frame is always shown first.

**Shutdown.** Stop the decoder, then close the pipeline, then release audio and the grabber. All the waits can be
interrupted, and the threads are daemon threads in case something goes wrong.

## Settings

`WORKER_COUNT` (6), `WINDOW` (6) and `INPUT_QUEUE_CAPACITY` (5) in `VideoPlayerModel`.

## Tests

`mvn test` takes under a second. The concurrency tests use fake transformers instead of real video, and `@Timeout` so
a deadlock fails the test instead of hanging.

- `OrderedBufferTest`: order, window, slow frame, reset, stale results, close.
- `FramePipelineTest`: order with random timing, errors, end of stream, reset, close.
- `FrameProcessorDeterminismTest`: separate chains give the same output.
- `CommandLineControllerTest`: argument parsing.

A passing test doesn't prove there are no races, so these are backed up by the reasoning above and the LTS model.

## Limitations

- The LTS model (`docs/LTS.md`) only covers one seek. It doesn't include the window, pausing, errors or shutdown.
- Still untested: lots of fast seeks under load, and the thread count going back down after stop.
- Toggling audio during playback can let one already-decoded chunk of audio through.

More detail is in `docs/DESIGN.md` and `docs/LTS.md`.
