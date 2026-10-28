/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. Kate Osborne - a1925594
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.JavaFXFrameConverter;

import javafx.animation.AnimationTimer;
import javafx.scene.image.Image;

/**
 * The model part of the video player model-view-controller architecture.
 *
 * <p>
 * Owns the FFmpeg decoder, playback clock, seeking logic and audio scheduling, and coordinates the threads that
 * play a video:
 * </p>
 * <ul>
 * <li>a <b>decoder</b> thread, the only thread that touches the {@link FFmpegFrameGrabber};</li>
 * <li>a pool of <b>worker</b> threads (see {@link FramePipeline}) that run the effect chain and convert frames,
 * each with its own processor chain;</li>
 * <li>the <b>JavaFX thread</b>, which polls the finished frames in order from {@link #pumpPlayback} and shows them
 * when due. It never blocks.</li>
 * </ul>
 * <p>
 * All playback state fields below are touched only by the JavaFX thread, except where marked otherwise. It
 * publishes decoded frames and playback state through callbacks supplied by its owning
 * {@link VideoPlayerController}, keeping it decoupled from the JavaFX view.
 * </p>
 */
public class VideoPlayerModel {
    private static final long NO_SEEK_REQUEST = -1; // Sentinel value used when no seek position is active.
    private static final long FIVE_SECONDS_US = 5_000_000L;
    private static final long AUDIO_LEAD_NS = 30_000_000L;
    private static final int WORKER_COUNT = 6;
    private static final int WINDOW = 6; // frames a worker may be ahead of the one being displayed
    private static final int INPUT_QUEUE_CAPACITY = 5;

    private final List<FrameEffect> frameEffects;
    // Filled by the decoder thread, drained by the JavaFX thread.
    private final Queue<PendingAudio> pendingAudio = new ConcurrentLinkedQueue<>();
    private final BiConsumer<Integer, Integer> videoSizeChangedHandler;
    private final Consumer<Image> frameReadyHandler;
    private final Consumer<String> statusChangedHandler;
    private final BiConsumer<Boolean, Boolean> playbackStateChangedHandler;
    private final Consumer<Boolean> audioOutputStateChangedHandler;

    // This is the critical wiring that allows the framework (JavaFX) to call
    // into our model logic every time it goes around its event loop. Every GUI
    // system has an event loop so that button clicks, key presses and window
    // resizes can be responded to. Most offer ways of running additional logic
    // in either idle time (when there are no pending UI events to process) or
    // on a regular heart beat, such as with this JavaFX AnimationTimer. Here
    // we just create a little anonymous local subclass and override the handle
    // method to call the method we want run every heart beat.
    private final AnimationTimer playbackTimer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            pumpPlayback(now);
        }
    };

    private File videoFile; // Path to the media that comes from the command line
    private FFmpegFrameGrabber grabber; // This is the 3rd party video media decoder. It deals in JavaCV Frame objects.
    private FramePipeline<ProcessedFrame> pipeline; // Worker pool + ordered hand-off. Replaced on every startPlayback.
    private Thread decoderThread; // Started/stopped only by the JavaFX thread.
    private volatile InfoVideo videoInfo; // Set once per playback by the decoder, read by the workers via each job.
    private AudioPlayer audioPlayer; // This is ours. It has some real time buffering smarts.
    private PreparedFrame preparedFrame; // Ours, but is just an Image with some meta-data added.
    private boolean playbackOpen; // True when playback is happening.
    private boolean pauseRequested;
    private volatile boolean audioOutputEnabled; // also read by the decoder thread
    private boolean audioAvailable; // written before the decoder starts, then read-only until it has stopped
    private boolean frameShownSinceReset; // lets a paused player still show the first frame after a seek
    private long currentTimestampUs;
    private long videoDurationUs = NO_SEEK_REQUEST;
    private int videoFrameDurationUs;
    private double frameRate;
    private int intFrameRate;
    private int totalVideoFrames;
    private long firstTimestampUs = NO_SEEK_REQUEST;
    private long logicalPlaybackBaseUs;
    private long playbackStartNs;
    private long pauseStartedNs;
    private long relativeSeekBaseUs = NO_SEEK_REQUEST;

    public VideoPlayerModel(
            boolean audioEnabled,
            List<FrameEffect> frameEffects,
            BiConsumer<Integer, Integer> videoSizeChangedHandler,
            Consumer<Image> frameReadyHandler,
            Consumer<String> statusChangedHandler,
            BiConsumer<Boolean, Boolean> playbackStateChangedHandler,
            Consumer<Boolean> audioOutputStateChangedHandler) {
        audioOutputEnabled = audioEnabled;
        this.videoSizeChangedHandler = videoSizeChangedHandler;
        this.frameReadyHandler = frameReadyHandler;
        this.statusChangedHandler = statusChangedHandler;
        this.playbackStateChangedHandler = playbackStateChangedHandler;
        this.audioOutputStateChangedHandler = audioOutputStateChangedHandler;
        this.frameEffects = List.copyOf(frameEffects);
    }

    public void play(File file) {
        videoFile = file;
        videoDurationUs = NO_SEEK_REQUEST;
        videoFrameDurationUs = 0;
        startPlayback(0, false, NO_SEEK_REQUEST);
    }

    public void startOver() {
        if (videoFile == null) {
            return;
        }

        seekTo(0);
    }

    public void backFiveSeconds() {
        seekRelative(-FIVE_SECONDS_US);
    }

    public void forwardFiveSeconds() {
        seekRelative(FIVE_SECONDS_US);
    }

    public void togglePause() {
        if (!playbackOpen) {
            if (videoFile != null) {
                startPlayback(displayableSeekTimestamp(currentTimestampUs), false, currentTimestampUs);
            }
            return;
        }

        pauseRequested = !pauseRequested;
        relativeSeekBaseUs = NO_SEEK_REQUEST;

        if (pauseRequested) {
            pauseStartedNs = System.nanoTime();
            flushAudioOutput();
        } else {
            resumePlaybackClock(System.nanoTime());
        }

        notifyPlaybackStateChanged();
    }

    public void toggleAudioOutput() {
        audioOutputEnabled = !audioOutputEnabled;
        pendingAudio.clear();
        flushAudioOutput();
        notifyAudioOutputStateChanged();
    }

    public boolean isAudioOutputEnabled() {
        return audioOutputEnabled;
    }

    public void stopPlayback() {
        closePlaybackResources();
        currentTimestampUs = 0;
        pauseRequested = false;
        notifyStatusChanged("Stopped");
        notifyFrameReady(null);
        notifyPlaybackStateChanged();
    }

    public void shutdown() {
        closePlaybackResources();
    }

    private void seekRelative(long offsetUs) {
        if (videoFile == null) {
            return;
        }

        long baseTimestampUs = relativeSeekBaseUs != NO_SEEK_REQUEST
                ? relativeSeekBaseUs
                : currentTimestampUs;
        seekTo(baseTimestampUs + offsetUs);
    }

    private void seekTo(long timestampUs) {
        long logicalTimestampUs = clampSeekTimestamp(timestampUs);
        long grabTimestampUs = displayableSeekTimestamp(logicalTimestampUs);

        if (!playbackOpen) {
            startPlayback(grabTimestampUs, pauseRequested, logicalTimestampUs);
            return;
        }

        try {
            // The grabber is only safe to reposition while the decoder is stopped.
            stopDecoder();
            pipeline.reset();
            grabber.setTimestamp(grabTimestampUs);
            resetPlaybackClock(logicalTimestampUs);
            flushAudioOutput();
            startDecoder();
            notifyPlaybackStateChanged();
        } catch (Exception e) {
            handlePlaybackError(e);
        }
    }

    private void startPlayback(long startTimestampUs, boolean initiallyPaused, long initialRelativeSeekBaseUs) {
        closePlaybackResources();

        pauseRequested = initiallyPaused;
        currentTimestampUs = initialRelativeSeekBaseUs != NO_SEEK_REQUEST
                ? initialRelativeSeekBaseUs
                : startTimestampUs;
        relativeSeekBaseUs = initialRelativeSeekBaseUs;

        notifyStatusChanged(videoFile.getName());

        try {
            openPlaybackResources(startTimestampUs);
            resetPlaybackClock(currentTimestampUs);
            playbackOpen = true;
            playbackTimer.start();
            startDecoder();
            notifyPlaybackStateChanged();
        } catch (Exception e) {
            handlePlaybackError(e);
        }
    }

    private void openPlaybackResources(long startTimestampUs) throws Exception {
        grabber = new FFmpegFrameGrabber(videoFile);
        grabber.setImageMode(FrameGrabber.ImageMode.COLOR);
        grabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
        grabber.start();

        notifyVideoSizeChanged(grabber.getImageWidth(), grabber.getImageHeight());

        frameRate = grabber.getFrameRate();
        intFrameRate = (int) Math.round(frameRate);
        videoFrameDurationUs = frameRate > 0
                ? (int) Math.round(1_000_000.0 / frameRate)
                : 0;
        totalVideoFrames = grabber.getLengthInVideoFrames();

        long durationUs = grabber.getLengthInTime();
        videoDurationUs = durationUs > 0
                ? durationUs
                : NO_SEEK_REQUEST;

        audioAvailable = grabber.hasAudio();
        audioPlayer = new AudioPlayer();
        if (audioAvailable) {
            audioPlayer.open(grabber.getSampleRate(), grabber.getAudioChannels());
        }

        if (startTimestampUs > 0) {
            grabber.setTimestamp(startTimestampUs);
        }

        pipeline = new FramePipeline<>(WORKER_COUNT, WINDOW, INPUT_QUEUE_CAPACITY, EffectTransformer::new);
    }

    private void resetPlaybackClock(long logicalTimestampUs) {
        pendingAudio.clear();
        preparedFrame = null;
        frameShownSinceReset = false;
        currentTimestampUs = logicalTimestampUs;
        relativeSeekBaseUs = logicalTimestampUs;
        firstTimestampUs = NO_SEEK_REQUEST;
        logicalPlaybackBaseUs = logicalTimestampUs;
        playbackStartNs = 0;
        pauseStartedNs = pauseRequested ? System.nanoTime() : 0;
    }

    private void resumePlaybackClock(long now) {
        if (pauseStartedNs > 0 && playbackStartNs > 0) {
            playbackStartNs += now - pauseStartedNs;
        }
        pauseStartedNs = 0;
    }

    private void startDecoder() {
        FFmpegFrameGrabber source = grabber;
        FramePipeline<ProcessedFrame> target = pipeline;
        // Read the generation here, on the JavaFX thread after any reset, so every job this decoder creates is
        // stamped with the generation it really belongs to.
        long generation = target.generation();
        decoderThread = new Thread(() -> decodeLoop(source, target, generation), "frame-decoder");
        decoderThread.setDaemon(true);
        decoderThread.start();
    }

    private void stopDecoder() {
        Thread thread = decoderThread;
        if (thread == null) {
            return;
        }
        decoderThread = null;
        thread.interrupt();
        boolean interrupted = false;
        while (thread.isAlive()) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                interrupted = true; // keep waiting: the grabber must not be closed while the decoder may use it
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    // Runs on the decoder thread, which is the only thread allowed to use 'source'. Frames are cloned because the
    // grabber reuses its Frame (and buffers) on the next grab. Jobs are numbered 0, 1, 2... from the start of the
    // decoder's run, which is what the OrderedBuffer orders by; the grabber's own frame number (which seeds the
    // effects) travels separately in the job.
    private void decodeLoop(FFmpegFrameGrabber source, FramePipeline<ProcessedFrame> target, long generation) {
        int sequence = 0;
        try {
            while (!Thread.currentThread().isInterrupted()) {
                Frame frame = grabFrame(source);
                if (frame == null) {
                    target.submit(FramePipeline.Job.end(sequence, generation));
                    return;
                }

                long timestampUs = source.getTimestamp();
                if (audioAvailable && frame.samples != null) {
                    queueAudio(timestampUs, frame);
                }

                if (frame.image == null) {
                    continue;
                }

                if (videoInfo == null) {
                    videoInfo = new InfoVideo(
                            mediaName(videoFile),
                            totalVideoFrames,
                            frame.imageWidth,
                            frame.imageHeight,
                            frame.imageDepth,
                            frame.imageChannels,
                            frame.imageStride,
                            frameRate,
                            intFrameRate,
                            videoFrameDurationUs,
                            source.getPixelFormat());
                }

                target.submit(FramePipeline.Job.frame(
                        sequence++, generation, frame.clone(), source.getFrameNumber(), timestampUs, videoInfo));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // we were asked to stop
        } catch (Exception e) {
            try {
                target.submit(FramePipeline.Job.failed(sequence, generation, e));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // This is called on a heart beat by the GUI thread and must never block. Jobs: (1) keep audio flowing if sound is
    // on, (2) if no frame is waiting, poll the pipeline for the next finished one (in order), and (3) display it when
    // it is due. The decoding and effects happen on other threads.
    private void pumpPlayback(long now) {
        if (!playbackOpen) {
            return;
        }

        writeDueAudio(now);

        // While paused we only want the first frame after a seek or start, so the picture appears.
        if (preparedFrame == null && (!pauseRequested || !frameShownSinceReset)) {
            pollPipeline();
            if (!playbackOpen) {
                return; // polling hit the end of the stream or an error and closed playback
            }
        }

        if (preparedFrame != null && preparedFrame.targetTimeNs() <= now) {
            displayPreparedFrame(now);
        }
    }

    private void pollPipeline() {
        Optional<FramePipeline.Output<ProcessedFrame>> polled = pipeline.poll();
        if (polled.isEmpty()) {
            return;
        }

        FramePipeline.Output<ProcessedFrame> output = polled.get();
        if (output.failure() != null) {
            handlePlaybackError(output.failure());
            return;
        }
        if (output.endOfStream()) {
            finishPlayback();
            return;
        }

        ProcessedFrame processed = output.value();
        long timestampUs = processed.timestampUs();
        if (firstTimestampUs == NO_SEEK_REQUEST) {
            firstTimestampUs = timestampUs;
            playbackStartNs = System.nanoTime();
            if (pauseRequested) {
                pauseStartedNs = playbackStartNs;
            }
        }

        long relativeTimestampUs = Math.max(0, timestampUs - firstTimestampUs);
        preparedFrame = new PreparedFrame(
                processed.image(),
                processed.frameNumber(),
                timestampUs,
                logicalPlaybackBaseUs + relativeTimestampUs,
                playbackStartNs + relativeTimestampUs * 1_000L,
                processed.completedAtNs());
    }

    private void displayPreparedFrame(long now) {
        PreparedFrame frame = preparedFrame;
        preparedFrame = null;
        frameShownSinceReset = true;
        currentTimestampUs = frame.logicalTimestampUs();
        relativeSeekBaseUs = NO_SEEK_REQUEST;

        // Dump some logging to the console once per second so that real time performance can be monitored.
        if (intFrameRate > 0 && frame.frameNumber() % intFrameRate == 0) {
            long remainingUs = (frame.targetTimeNs() - frame.preparedAtNs()) / 1_000L;
            if (remainingUs < 0) {
                System.out.printf("Frame headroom is \u001B[31m%5dus\u001B[0m out of %dus spare.%n",
                        remainingUs,
                        videoFrameDurationUs);
            } else {
                System.out.printf("Frame headroom is \u001B[32m%5dus\u001B[0m out of %dus spare.%n",
                        remainingUs,
                        videoFrameDurationUs);
            }
        }

        notifyFrameReady(frame.image());
    }

    private void queueAudio(long timestampUs, Frame frame) {
        if (!audioOutputEnabled || audioPlayer == null) {
            return;
        }

        byte[] samples = audioPlayer.copySamples(frame);
        if (samples.length > 0) {
            pendingAudio.add(new PendingAudio(timestampUs, samples));
        }
    }

    private void writeDueAudio(long now) {
        // Handle some obvious early exits
        if (!audioOutputEnabled || audioPlayer == null) {
            pendingAudio.clear();
            return;
        }
        if (pauseRequested || firstTimestampUs == NO_SEEK_REQUEST || playbackStartNs <= 0) {
            return;
        }

        // Here is the real time dependent logic
        long dueTimestampUs = firstTimestampUs + (now + AUDIO_LEAD_NS - playbackStartNs) / 1_000L;
        while (!pendingAudio.isEmpty()) {
            PendingAudio audio = pendingAudio.peek();
            if (audio.timestampUs() > dueTimestampUs) {
                return;
            }

            int written = audioPlayer.write(audio.samples(), audio.offset(), audio.remaining());
            if (written <= 0) {
                return;
            }

            audio.advance(written);
            if (!audio.finished()) {
                return;
            }
            pendingAudio.remove();
        }
    }

    private void finishPlayback() {
        long endTimestampUs = videoDurationUs != NO_SEEK_REQUEST
                ? videoDurationUs
                : currentTimestampUs;

        closePlaybackResources();
        pauseRequested = true;
        currentTimestampUs = endTimestampUs;
        relativeSeekBaseUs = endTimestampUs;
        notifyStatusChanged("Playback finished");
        notifyPlaybackStateChanged();
    }

    private void handlePlaybackError(Exception e) {
        e.printStackTrace();
        closePlaybackResources();
        pauseRequested = false;
        notifyStatusChanged("Error: " + e.getMessage());
        notifyPlaybackStateChanged();
    }

    private void closePlaybackResources() {
        playbackTimer.stop();
        playbackOpen = false;

        // Stop the producer first, then the consumers, and only then release what they were using.
        stopDecoder();
        if (pipeline != null) {
            pipeline.close();
            pipeline = null;
        }
        videoInfo = null;

        preparedFrame = null;
        pendingAudio.clear();
        firstTimestampUs = NO_SEEK_REQUEST;
        playbackStartNs = 0;
        pauseStartedNs = 0;
        audioAvailable = false;

        if (audioPlayer != null) {
            audioPlayer.close();
            audioPlayer = null;
        }

        if (grabber != null) {
            try {
                grabber.stop();
            } catch (Exception e) {
                // The resource is being closed; there is no useful recovery action.
            }
            try {
                grabber.close();
            } catch (Exception e) {
                // The resource is being closed; there is no useful recovery action.
            }
            grabber = null;
        }

    }

    private String mediaName(File file) {
        String fileName = file.getName();
        int extensionStart = fileName.lastIndexOf('.');
        if (extensionStart <= 0) {
            return fileName;
        }
        return fileName.substring(0, extensionStart);
    }

    private long clampSeekTimestamp(long timestampUs) {
        long clampedTimestampUs = Math.max(0, timestampUs);
        long durationUs = videoDurationUs;
        if (durationUs != NO_SEEK_REQUEST) {
            clampedTimestampUs = Math.min(clampedTimestampUs, durationUs);
        }
        return clampedTimestampUs;
    }

    private long displayableSeekTimestamp(long logicalTimestampUs) {
        long durationUs = videoDurationUs;
        if (durationUs == NO_SEEK_REQUEST || logicalTimestampUs < durationUs) {
            return logicalTimestampUs;
        }

        int frameDurationUs = videoFrameDurationUs;
        if (frameDurationUs <= 0) {
            return Math.max(0, durationUs - 1);
        }

        return Math.max(0, durationUs - frameDurationUs);
    }

    // Each worker gets its own chain, because some processors keep scratch buffers. Seeds depend only on the effect
    // and how many times it has already appeared in the list, so every chain produces identical output.
    private List<FrameProcessor> createProcessorChain() {
        Map<FrameEffect, Integer> occurrences = new EnumMap<>(FrameEffect.class);
        List<FrameProcessor> chain = new ArrayList<>();
        for (FrameEffect effect : frameEffects) {
            int occurrence = occurrences.merge(effect, 1, Integer::sum) - 1;
            chain.add(effect.create(occurrence));
        }
        return chain;
    }

    private Frame grabFrame(FFmpegFrameGrabber source) throws Exception {
        if (audioAvailable) {
            return source.grab();
        }
        return source.grabImage();
    }

    private void flushAudioOutput() {
        if (audioPlayer != null) {
            audioPlayer.flush();
        }
    }

    private void notifyVideoSizeChanged(int width, int height) {
        videoSizeChangedHandler.accept(width, height);
    }

    private void notifyFrameReady(Image image) {
        frameReadyHandler.accept(image);
    }

    private void notifyStatusChanged(String status) {
        statusChangedHandler.accept(status);
    }

    private void notifyPlaybackStateChanged() {
        playbackStateChangedHandler.accept(playbackOpen, pauseRequested);
    }

    private void notifyAudioOutputStateChanged() {
        audioOutputStateChangedHandler.accept(audioOutputEnabled);
    }

    // A frame that has been through the effects and converted, as produced by a worker.
    private record ProcessedFrame(Image image, int frameNumber, long timestampUs, long completedAtNs) {
    }

    // One per worker thread: that worker's private effect chain and image converter.
    private final class EffectTransformer implements FramePipeline.Transformer<ProcessedFrame> {
        private final List<FrameProcessor> chain = createProcessorChain();
        private final JavaFXFrameConverter converter = new JavaFXFrameConverter();
        private InfoVideo initialisedFor;

        @Override
        public ProcessedFrame transform(FramePipeline.Job job) throws Exception {
            if (initialisedFor != job.videoInfo()) {
                for (FrameProcessor processor : chain) {
                    processor.initialise(job.videoInfo());
                }
                initialisedFor = job.videoInfo();
            }

            InfoFrame info = new InfoFrame(job.frameNumber(), job.timestampUs());
            for (FrameProcessor processor : chain) {
                processor.process(job.frame(), info);
            }

            Image image = converter.convert(job.frame());
            return new ProcessedFrame(image, job.frameNumber(), job.timestampUs(), System.nanoTime());
        }

        @Override
        public void close() {
            converter.close();
        }
    }

    private record PreparedFrame(
            Image image,
            int frameNumber,
            long mediaTimestampUs,
            long logicalTimestampUs,
            long targetTimeNs,
            long preparedAtNs) {
    }

    private static class PendingAudio {
        private final long timestampUs;
        private final byte[] samples;
        private int offset;

        PendingAudio(long timestampUs, byte[] samples) {
            this.timestampUs = timestampUs;
            this.samples = samples;
        }

        long timestampUs() {
            return timestampUs;
        }

        byte[] samples() {
            return samples;
        }

        int offset() {
            return offset;
        }

        int remaining() {
            return samples.length - offset;
        }

        void advance(int byteCount) {
            offset += byteCount;
        }

        boolean finished() {
            return offset >= samples.length;
        }
    }
}
