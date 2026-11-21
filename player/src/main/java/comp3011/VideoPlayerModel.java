/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. Yuxuan Bai (a2993969), with OpenAI Codex AI assistance.
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

import static org.bytedeco.ffmpeg.global.avutil.AVERROR_EOF;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;

import javafx.animation.AnimationTimer;
import javafx.beans.property.ReadOnlyLongProperty;
import javafx.beans.property.ReadOnlyLongWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;

/**
 * The model part of the video player model-view-controller architecture.
 *
 * <p>
 * Coordinates a playback worker, an audio-output worker, and the JavaFX thread.
 * The playback worker owns decoding, frame processing, and the shared playback
 * clock; the audio worker writes timestamped PCM packets; JavaFX only displays
 * due frames and updates the view through callbacks.
 * </p>
 */
public class VideoPlayerModel {
    private static final long NO_SEEK_REQUEST = -1; // Sentinel value used when no seek position is active.
    private static final long FIVE_SECONDS_US = 5_000_000L;
    private static final long AUDIO_LEAD_NS = 30_000_000L;
    private static final int FRAME_QUEUE_CAPACITY = 3;
    private static final int AUDIO_QUEUE_CAPACITY = 64;
    private static final int DISPLAY_BYTES_PER_PIXEL = 4;

    private final List<FrameProcessor> frameProcessors;
    private final int firstSubtitleProcessor;
    private final boolean effectsFollowSubtitles;
    private final FrameSubtitler captionRasterizer;
    private final PlaybackProfiler playbackProfiler;
    // The playback worker consumes commands and produces frames and UI events.
    private final BlockingQueue<PlaybackCommand> playbackCommands = new LinkedBlockingQueue<>();
    // A small bounded queue limits image memory and pauses decoding when the display falls behind.
    private final BlockingQueue<PreparedFrame> preparedFrames = new ArrayBlockingQueue<>(FRAME_QUEUE_CAPACITY);
    // A3 NOTE: Recycle BGRA pixel arrays after JavaFX copies them to limit per-frame allocations.
    private final BlockingQueue<byte[]> reusablePixels = new ArrayBlockingQueue<>(2 * (FRAME_QUEUE_CAPACITY + 2));
    private final BlockingQueue<byte[]> reusableSubtitleSources = new ArrayBlockingQueue<>(FRAME_QUEUE_CAPACITY + 2);
    // Decoded PCM packets cross from the playback worker to the audio-output worker.
    private final BlockingQueue<AudioPacket> audioPackets = new ArrayBlockingQueue<>(AUDIO_QUEUE_CAPACITY);
    // Only audioOutputWorker creates, uses, flushes, or closes AudioPlayer in response to these commands.
    private final BlockingQueue<AudioOutputCommand> audioOutputCommands = new LinkedBlockingQueue<>();
    // Worker threads publish immutable events here; AnimationTimer applies them on the JavaFX thread.
    private final BlockingQueue<PlaybackEvent> playbackEvents = new LinkedBlockingQueue<>();
    private final AtomicLong displayedTimestampUs = new AtomicLong();
    private final AtomicLong relativeSeekBaseUs = new AtomicLong(NO_SEEK_REQUEST);
    private final AtomicLong activePlaybackGeneration = new AtomicLong();
    private final AtomicReference<AudioClock> audioClock = new AtomicReference<>(
            new AudioClock(0, NO_SEEK_REQUEST, 0, true, false));
    private final AtomicBoolean shutdownCommandQueued = new AtomicBoolean();
    private volatile boolean audioOutputEnabledSnapshot;
    // Read and updated only by the JavaFX AnimationTimer while draining playbackEvents.
    private boolean pausedSnapshot;
    private final BiConsumer<Integer, Integer> videoSizeChangedHandler;
    private final Consumer<Image> frameReadyHandler;
    private final Consumer<String> statusChangedHandler;
    private final BiConsumer<Boolean, Boolean> playbackStateChangedHandler;
    private final Consumer<Boolean> audioOutputStateChangedHandler;
    // A3 NOTE: The loader prepares captions off-thread; JavaFX chooses text using the displayed media timestamp.
    private final Consumer<String> subtitleTextChangedHandler;
    private final AtomicBoolean subtitlesEnabled;
    private final SubtitleLoader subtitleLoader;
    // A3 NOTE: These observable display properties are owned by JavaFX, keeping UI listeners on its application thread.
    private final ReadOnlyLongWrapper displayedMediaTimestampUs = new ReadOnlyLongWrapper(NO_SEEK_REQUEST);
    private final ReadOnlyLongWrapper displayedDurationUs = new ReadOnlyLongWrapper(NO_SEEK_REQUEST);
    private final ReadOnlyObjectWrapper<SubtitleLoader.Progress> subtitleProgress =
            new ReadOnlyObjectWrapper<>(new SubtitleLoader.Progress(SubtitleLoader.State.OFF, 0, 0));
    // A3 NOTE: Allow initial caption preparation for at most 10 s after media opens, then continue in the background.
    private static final long INITIAL_SUBTITLE_WAIT_NS = TimeUnit.SECONDS.toNanos(10);
    private boolean waitingForSubtitles;
    private long subtitleWaitDeadlineNs;
    private long requestedSubtitleVersion;
    private String displayedSubtitleText = "";
    private WritableImage displayImage;
    private long displayImageGeneration;
    // JavaFX retains the current frame's renderings for immediate CC changes without resetting playback.
    private PreparedFrame displayedFrame;
    private PlaybackEvent.SubtitleRendered lateSubtitle;
    private FrameSubtitler.Caption paintedCaption;
    private byte[] subtitlePixels = new byte[0];

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

    private final Thread playbackWorker;
    private final Thread audioOutputWorker;
    private volatile boolean shutdownRequested;

    // The fields below are owned by playbackWorker and are not read by the UI thread.
    private File videoFile;
    private long subtitleVersion;
    private FFmpegFrameGrabber grabber; // This is the 3rd party video media decoder. It deals in JavaCV Frame objects.
    private boolean playbackOpen; // True when playback is happening.
    private boolean pauseRequested;
    private boolean audioOutputEnabled;
    private boolean audioAvailable;
    private boolean frameProcessorsInitialised;
    private long currentTimestampUs;
    // A3 NOTE: Container timestamps may have a nonzero origin; captions use normalized, video-relative microseconds.
    private long mediaStartUs;
    private long openedMediaSize = -1;
    private long videoDurationUs = NO_SEEK_REQUEST;
    private int videoFrameDurationUs;
    private double frameRate;
    private int intFrameRate;
    private int totalVideoFrames;
    private long firstTimestampUs = NO_SEEK_REQUEST;
    // A3 NOTE: Discard pictures decoded before the requested seek area so seeking does not show an old frame.
    private long earliestSeekFrameUs = NO_SEEK_REQUEST;
    private long logicalPlaybackBaseUs;
    private long playbackStartNs;
    private long pauseStartedNs;
    private long playbackGeneration;
    private boolean audioOutputOpenRequested;

    /** A3 NOTE: Extends the existing playback workers with an independent subtitle loader and UI text callback. */
    public VideoPlayerModel(
            boolean audioEnabled,
            boolean subtitlesEnabled,
            List<FrameProcessorOption> frameProcessorOptions,
            BiConsumer<Integer, Integer> videoSizeChangedHandler,
            Consumer<Image> frameReadyHandler,
            Consumer<String> statusChangedHandler,
            BiConsumer<Boolean, Boolean> playbackStateChangedHandler,
            Consumer<Boolean> audioOutputStateChangedHandler,
            Consumer<String> subtitleTextChangedHandler,
            SubtitleLoader subtitleLoader) {
        audioOutputEnabled = audioEnabled;
        audioOutputEnabledSnapshot = audioEnabled;
        this.videoSizeChangedHandler = videoSizeChangedHandler;
        this.frameReadyHandler = frameReadyHandler;
        this.statusChangedHandler = statusChangedHandler;
        this.playbackStateChangedHandler = playbackStateChangedHandler;
        this.audioOutputStateChangedHandler = audioOutputStateChangedHandler;
        this.subtitleTextChangedHandler = subtitleTextChangedHandler;
        this.subtitleLoader = subtitleLoader;
        this.subtitlesEnabled = new AtomicBoolean(subtitlesEnabled);
        var options = new ArrayList<>(frameProcessorOptions);
        int firstSubtitle = options.indexOf(FrameProcessorOption.SUBTITLE_FRAMES);
        if (firstSubtitle < 0) {
            // The CC button can enable a final subtitle stage even without a launch option.
            firstSubtitle = options.size();
            options.add(FrameProcessorOption.SUBTITLE_FRAMES);
        }
        firstSubtitleProcessor = firstSubtitle;
        effectsFollowSubtitles = options.subList(firstSubtitle + 1, options.size()).stream()
                .anyMatch(option -> option != FrameProcessorOption.SUBTITLE_FRAMES);
        frameProcessors = options.stream().map(FrameProcessorOption::createProcessor).toList();
        captionRasterizer = (FrameSubtitler) frameProcessors.get(firstSubtitleProcessor);
        playbackProfiler = new PlaybackProfiler(this.frameProcessors);

        audioOutputWorker = new Thread(this::runAudioOutputWorker, "video-audio-output-worker");
        audioOutputWorker.setDaemon(true);
        audioOutputWorker.start();

        playbackWorker = new Thread(this::runPlaybackWorker, "video-playback-worker");
        playbackWorker.setDaemon(true);
        playbackWorker.start();
        playbackTimer.start();
    }

    /** A3 NOTE: Starts a new caption version and passes its identity/preparation pause to the playback worker. */
    public void play(File file) {
        displayedDurationUs.set(NO_SEEK_REQUEST);
        requestedSubtitleVersion = subtitleLoader.setVideo(file);
        waitingForSubtitles = subtitlesEnabled.get();
        subtitleWaitDeadlineNs = 0;
        subtitleLoader.setEnabled(waitingForSubtitles);
        enqueueCommand(new PlaybackCommand.Play(file, requestedSubtitleVersion, waitingForSubtitles));
    }

    /** A3 NOTE: Videos opened through the file picker always begin with English captions enabled. */
    void playWithSubtitles(File file) {
        subtitlesEnabled.set(true);
        play(file);
    }

    public void startOver() {
        seekToPosition(0);
    }

    /** A3 NOTE: Routes timeline seeks through the worker command queue; the UI never operates the decoder directly. */
    public void seekToPosition(long timestampUs) {
        enqueueCommand(new PlaybackCommand.SeekTo(timestampUs));
    }

    /** A3 NOTE: Exposes the displayed video's microsecond position for the timeline, independently of scheduling time. */
    ReadOnlyLongProperty displayedMediaTimestampUsProperty() {
        return displayedMediaTimestampUs.getReadOnlyProperty();
    }

    /** A3 NOTE: Publishes the opened media duration so the view can enable and scale its seek slider. */
    ReadOnlyLongProperty displayedDurationUsProperty() {
        return displayedDurationUs.getReadOnlyProperty();
    }

    /** A3 NOTE: Publishes caption status snapshots without exposing the loader's mutable scheduling state. */
    ReadOnlyObjectProperty<SubtitleLoader.Progress> subtitleProgressProperty() {
        return subtitleProgress.getReadOnlyProperty();
    }

    public void backFiveSeconds() {
        enqueueCommand(new PlaybackCommand.SeekBy(-FIVE_SECONDS_US));
    }

    public void forwardFiveSeconds() {
        enqueueCommand(new PlaybackCommand.SeekBy(FIVE_SECONDS_US));
    }

    /** A3 NOTE: A user's pause/play choice overrides automatic waiting for initial subtitle preparation. */
    public void togglePause() {
        waitingForSubtitles = false;
        enqueueCommand(PlaybackCommand.TogglePause.INSTANCE);
    }

    public void toggleAudioOutput() {
        enqueueCommand(PlaybackCommand.ToggleAudio.INSTANCE);
    }

    public boolean isAudioOutputEnabled() {
        return audioOutputEnabledSnapshot;
    }

    public void stopPlayback() {
        waitingForSubtitles = false;
        enqueueCommand(PlaybackCommand.Stop.INSTANCE);
    }

    /** A3 NOTE: Toggles preparation/display independently of visual effects and releases any caption startup wait. */
    public void toggleSubtitles() {
        boolean enabled = !subtitlesEnabled.get();
        subtitlesEnabled.set(enabled);
        subtitleLoader.setEnabled(enabled);
        if (!enabled) {
            resumeAfterSubtitlePreparation();
        }
        refreshSubtitle();
    }

    /** A3 NOTE: Supplies the current caption state to the controller's CC button and keyboard shortcut. */
    public boolean areSubtitlesEnabled() {
        return subtitlesEnabled.get();
    }

    /** A3 NOTE: Stops playback/audio workers and the subtitle loader; each resource owner performs its own cleanup. */
    public void shutdown() {
        if (shutdownCommandQueued.compareAndSet(false, true)) {
            shutdownRequested = true;
            audioPackets.clear();
            audioClock.set(new AudioClock(activePlaybackGeneration.get(), NO_SEEK_REQUEST, 0, true, false));
            audioOutputCommands.offer(AudioOutputCommand.Shutdown.INSTANCE);
            enqueueCommand(PlaybackCommand.Shutdown.INSTANCE);
            // Wake the worker if it is waiting for a command so it can release native resources promptly.
            playbackWorker.interrupt();
            subtitleLoader.close();
        }
    }

    /** Keeps decoder and playback-state mutations serialized on playbackWorker. */
    private void enqueueCommand(PlaybackCommand command) {
        if (!shutdownCommandQueued.get() || command == PlaybackCommand.Shutdown.INSTANCE) {
            playbackCommands.offer(command);
        }
    }

    /** Owns the decoder and frame converter, checking queued controls between frame-preparation steps. */
    private void runPlaybackWorker() {
        try {
            while (!shutdownRequested) {
                PlaybackCommand command = playbackCommands.poll();
                if (command != null) {
                    handlePlaybackCommand(command);
                }
                if (shutdownRequested) {
                    break;
                }

                if (playbackOpen) {
                    if (!pauseRequested && preparedFrames.remainingCapacity() > 0) {
                        prepareNextFrame();
                    } else if (command == null) {
                        command = playbackCommands.poll(5, TimeUnit.MILLISECONDS);
                        if (command != null) {
                            handlePlaybackCommand(command);
                        }
                    }
                } else if (command == null) {
                    command = playbackCommands.poll(100, TimeUnit.MILLISECONDS);
                    if (command != null) {
                        handlePlaybackCommand(command);
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            shutdownRequested = true;
        } finally {
            closePlaybackResources();
            audioOutputCommands.offer(AudioOutputCommand.Shutdown.INSTANCE);
        }
    }

    /** Applies UI commands on playbackWorker so JavaFX callbacks never mutate decoder state directly. */
    private void handlePlaybackCommand(PlaybackCommand command) {
        if (command instanceof PlaybackCommand.Play playCommand) {
            videoFile = playCommand.file();
            subtitleVersion = playCommand.subtitleVersion();
            videoDurationUs = NO_SEEK_REQUEST;
            videoFrameDurationUs = 0;
            currentTimestampUs = 0;
            startPlayback(0, playCommand.prepareSubtitles(), NO_SEEK_REQUEST);
        } else if (command instanceof PlaybackCommand.ResumePrepared resume) {
            if (resume.subtitleVersion() == subtitleVersion && playbackOpen && pauseRequested) {
                togglePauseOnPlaybackWorker();
            }
        } else if (command instanceof PlaybackCommand.RenderSubtitle render) {
            renderLateSubtitle(render);
        } else if (command instanceof PlaybackCommand.SeekTo seekCommand) {
            if (videoFile != null) {
                seekTo(seekCommand.timestampUs());
            }
        } else if (command instanceof PlaybackCommand.SeekBy seekCommand) {
            seekRelative(seekCommand.offsetUs());
        } else if (command instanceof PlaybackCommand.AudioFailure audioFailure) {
            handleAudioFailure(audioFailure);
        } else if (command == PlaybackCommand.TogglePause.INSTANCE) {
            togglePauseOnPlaybackWorker();
        } else if (command == PlaybackCommand.ToggleAudio.INSTANCE) {
            audioOutputEnabled = !audioOutputEnabled;
            audioOutputEnabledSnapshot = audioOutputEnabled;
            audioPackets.clear();
            publishAudioClock();
            audioOutputCommands.offer(new AudioOutputCommand.Flush(playbackGeneration));
            notifyAudioOutputStateChanged();
        } else if (command == PlaybackCommand.Stop.INSTANCE) {
            playbackGeneration++;
            activePlaybackGeneration.set(playbackGeneration);
            closePlaybackResources();
            currentTimestampUs = 0;
            displayedTimestampUs.set(0);
            relativeSeekBaseUs.set(NO_SEEK_REQUEST);
            pauseRequested = false;
            notifyStatusChanged("Stopped");
            notifyFrameCleared();
            notifyPlaybackStateChanged();
        } else if (command == PlaybackCommand.Shutdown.INSTANCE) {
            shutdownRequested = true;
        }
    }

    /** Changes pause state on the owner thread and updates the audio clock and buffered output with it. */
    private void togglePauseOnPlaybackWorker() {
        if (!playbackOpen) {
            if (videoFile != null) {
                long timestampUs = displayedTimestampUs.get();
                startPlayback(displayableSeekTimestamp(timestampUs), false, timestampUs);
            }
            return;
        }

        pauseRequested = !pauseRequested;
        relativeSeekBaseUs.set(NO_SEEK_REQUEST);

        if (pauseRequested) {
            pauseStartedNs = System.nanoTime();
            // Keep audio the decoder has already read ahead for the resumed playback clock.
            publishAudioClock();
        } else {
            resumePlaybackClock(System.nanoTime());
            publishAudioClock();
        }

        notifyPlaybackStateChanged();
    }

    /** Bases relative seeks on the visible frame, not a frame that may still be decoding. */
    private void seekRelative(long offsetUs) {
        if (videoFile == null) {
            return;
        }

        long seekBaseUs = relativeSeekBaseUs.get();
        long baseTimestampUs = seekBaseUs != NO_SEEK_REQUEST
                ? seekBaseUs
                : displayedTimestampUs.get();
        seekTo(baseTimestampUs + offsetUs);
    }

    /** Invalidates queued work before moving the decoder so output from the previous position cannot reappear. */
    private void seekTo(long timestampUs) {
        long logicalTimestampUs = clampSeekTimestamp(timestampUs);
        long grabTimestampUs = displayableSeekTimestamp(logicalTimestampUs);

        if (!playbackOpen) {
            startPlayback(grabTimestampUs, pauseRequested, logicalTimestampUs);
            return;
        }

        try {
            // Advance the generation first so any already-in-flight old frame or audio packet is rejected.
            playbackGeneration++;
            activePlaybackGeneration.set(playbackGeneration);
            discardPreparedFrames();
            audioPackets.clear();
            audioClock.set(new AudioClock(playbackGeneration, NO_SEEK_REQUEST, 0, true, false));
            audioOutputCommands.offer(new AudioOutputCommand.Reset(playbackGeneration));
            // Retain the visible frame until the decoder has a replacement for the new position.
            grabber.setTimestamp(grabTimestampUs);
            resetPlaybackClock(logicalTimestampUs, grabTimestampUs);
            prepareNextFrame();
            notifyPlaybackStateChanged();
        } catch (Exception e) {
            handlePlaybackError(e);
        }
    }

    private void startPlayback(long startTimestampUs, boolean initiallyPaused, long initialRelativeSeekBaseUs) {
        // A fresh generation separates this run from frames and packets left by the previous run.
        playbackGeneration++;
        activePlaybackGeneration.set(playbackGeneration);
        closePlaybackResources();

        pauseRequested = initiallyPaused;
        currentTimestampUs = initialRelativeSeekBaseUs != NO_SEEK_REQUEST
                ? initialRelativeSeekBaseUs
                : startTimestampUs;
        displayedTimestampUs.set(currentTimestampUs);
        relativeSeekBaseUs.set(initialRelativeSeekBaseUs);

        notifyFrameCleared();
        notifyStatusChanged(videoFile.getName());

        try {
            openPlaybackResources(startTimestampUs);
            resetPlaybackClock(currentTimestampUs, startTimestampUs);
            playbackOpen = true;
            prepareNextFrame();
            notifyPlaybackStateChanged();
        } catch (Exception e) {
            handlePlaybackError(e);
        }
    }

    /** A3 NOTE: Downmixes playback to stereo so surround center-channel dialogue reaches ordinary speakers. */
    static FFmpegFrameGrabber createPlaybackGrabber(File file) {
        FFmpegFrameGrabber playbackGrabber = new FFmpegFrameGrabber(file);
        playbackGrabber.setImageMode(FrameGrabber.ImageMode.COLOR);
        playbackGrabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
        playbackGrabber.setAudioChannels(2);
        return playbackGrabber;
    }

    /** Opens native decode resources on playbackWorker and asks audioOutputWorker to open its device. */
    private void openPlaybackResources(long startTimestampUs) throws Exception {
        openedMediaSize = Files.size(videoFile.toPath());
        grabber = createPlaybackGrabber(videoFile);
        grabber.start();
        if (!grabber.hasVideo()) {
            throw new IOException("The selected file does not contain a video track");
        }
        mediaStartUs = AudioExtractor.mediaStartUs(grabber);

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
        playbackEvents.offer(new PlaybackEvent.MediaOpened(grabber.getImageWidth(), grabber.getImageHeight(),
                videoDurationUs));

        audioAvailable = grabber.hasAudio();
        subtitleLoader.mediaOpened(subtitleVersion, audioAvailable, videoDurationUs);
        if (audioAvailable) {
            audioOutputOpenRequested = true;
            audioOutputCommands.offer(new AudioOutputCommand.Open(
                    playbackGeneration,
                    grabber.getSampleRate(),
                    grabber.getAudioChannels()));
        }

        if (startTimestampUs > 0) {
            grabber.setTimestamp(startTimestampUs);
        }
    }

    /** A3 NOTE: Resets playback timing and records the earliest acceptable picture after a seek. */
    private void resetPlaybackClock(long logicalTimestampUs, long seekTimestampUs) {
        discardPreparedFrames();
        audioPackets.clear();
        currentTimestampUs = logicalTimestampUs;
        displayedTimestampUs.set(logicalTimestampUs);
        relativeSeekBaseUs.set(logicalTimestampUs);
        firstTimestampUs = NO_SEEK_REQUEST;
        earliestSeekFrameUs = seekTimestampUs > 0
                ? Math.max(0, seekTimestampUs - videoFrameDurationUs)
                : NO_SEEK_REQUEST;
        logicalPlaybackBaseUs = logicalTimestampUs;
        playbackStartNs = 0;
        pauseStartedNs = pauseRequested ? System.nanoTime() : 0;
        publishAudioClock();
    }

    private void resumePlaybackClock(long now) {
        if (pauseStartedNs > 0 && playbackStartNs > 0) {
            long pauseDurationNs = now - pauseStartedNs;
            // Move the clock forward by the paused duration so media time does not advance while paused.
            playbackStartNs += pauseDurationNs;
            retimeQueuedFrames(pauseDurationNs);
        }
        pauseStartedNs = 0;
    }

    private void retimeQueuedFrames(long pauseDurationNs) {
        if (pauseDurationNs <= 0 || preparedFrames.isEmpty()) {
            return;
        }

        // Reinsert only the frames that were queued when retiming began.
        // Polling until empty would revisit each reinserted frame forever.
        int queuedFrameCount = preparedFrames.size();
        for (int index = 0; index < queuedFrameCount; index++) {
            PreparedFrame frame = preparedFrames.poll();
            if (frame == null) {
                break;
            }
            preparedFrames.offer(frame.delayedBy(pauseDurationNs));
        }
    }

    /** A3 NOTE: Publishes a paused seek preview immediately; running playback uses the scheduled frame queue. */
    private void prepareNextFrame() {
        if (!playbackOpen || preparedFrames.remainingCapacity() == 0) {
            return;
        }

        try {
            PreparedFrame frame = readNextVideoFrame();
            if (frame == null) {
                return;
            }
            if (pauseRequested) {
                playbackEvents.offer(new PlaybackEvent.PausedFrameReady(frame));
            } else if (!preparedFrames.offer(frame)) {
                releasePreparedFrame(frame);
            }
        } catch (Exception e) {
            handlePlaybackError(e);
        }
    }

    /** Decodes and processes one display frame while queueing audio samples from the same media timestamp. */
    private PreparedFrame readNextVideoFrame() throws Exception {
        while (playbackOpen) {
            long decodeStartedNs = playbackProfiler.enabled() ? System.nanoTime() : 0;
            Frame frame = grabFrame();
            if (playbackProfiler.enabled()) {
                playbackProfiler.recordDecode(System.nanoTime() - decodeStartedNs);
            }
            if (frame == null) {
                if (mediaReadInterrupted()) {
                    interruptPlayback();
                    return null;
                }
                if (earliestSeekFrameUs != NO_SEEK_REQUEST) {
                    // A file can continue with audio after its final picture. Fall back to the nearest decoded area.
                    earliestSeekFrameUs = NO_SEEK_REQUEST;
                    audioPackets.clear();
                    grabber.setTimestamp(displayableSeekTimestamp(logicalPlaybackBaseUs));
                    continue;
                }
                finishPlayback();
                return null;
            }

            long timestampUs = grabber.getTimestamp();
            if (audioAvailable && frame.samples != null) {
                queueAudio(timestampUs, frame);
            }

            if (frame.image == null) {
                continue;
            }
            long mediaTimestampUs = Math.max(0, timestampUs - mediaStartUs);
            if (mediaTimestampUs < earliestSeekFrameUs) {
                // A seek can reach the audio target while decoded video still trails by several frames.
                continue;
            }
            earliestSeekFrameUs = NO_SEEK_REQUEST;

            if (!frameProcessorsInitialised) {
                initialiseFrameProcessors(new InfoVideo(
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
                        grabber.getPixelFormat()));
                frameProcessorsInitialised = true;
            }

            if (firstTimestampUs == NO_SEEK_REQUEST) {
                firstTimestampUs = timestampUs;
                playbackStartNs = System.nanoTime();
                if (pauseRequested) {
                    pauseStartedNs = playbackStartNs;
                }
                publishAudioClock();
            }

            int frameNumber = grabber.getFrameNumber();
            return prepareFrame(frame, new InfoFrame(frameNumber, timestampUs), mediaTimestampUs);
        }

        return null;
    }

    /** Shares the stages before the first subtitle between the two versions of an ordered frame. */
    private PreparedFrame prepareFrame(Frame frame, InfoFrame info, long mediaTimestampUs) throws Exception {
        var cue = subtitleLoader.cueAt(subtitleVersion, mediaTimestampUs);
        var caption = captionRasterizer.prepareCaption(cue.text(), frame.imageWidth, frame.imageHeight);
        byte[] sourcePixels = null;
        byte[] captionedPixels = null;
        try {
            processFrame(frame, info, 0, firstSubtitleProcessor, null);
            if (effectsFollowSubtitles) {
                if (caption != null || cue.pending()) {
                    ByteBuffer image = (ByteBuffer) frame.image[0];
                    sourcePixels = borrowPixels(reusableSubtitleSources, image.limit());
                    image.get(0, sourcePixels);
                    if (caption != null) {
                        captionedPixels = renderOrderedSubtitle(frame, info, caption);
                        image.put(0, sourcePixels);
                    }
                }
                processFrame(frame, info, firstSubtitleProcessor, frameProcessors.size(), null);
            }

            // If subtitles are the final stages, JavaFX can blend the small rectangle as it displays the frame.
            byte[] pixels = copyPixels(frame);
            playbackProfiler.recordCompletedFrame();
            long relativeTimestampUs = Math.max(0, info.timestampUs() - firstTimestampUs);
            long logicalTimestampUs = logicalPlaybackBaseUs + relativeTimestampUs;
            long targetTimeNs = playbackStartNs + relativeTimestampUs * 1_000L;
            currentTimestampUs = logicalTimestampUs;

            var pending = caption == null && sourcePixels != null
                    ? new PreparedFrame.SubtitleSource(frame, info, sourcePixels)
                    : null;
            var result = new PreparedFrame(playbackGeneration, subtitleVersion, pixels, frame.imageWidth,
                    frame.imageHeight, mediaTimestampUs, logicalTimestampUs, targetTimeNs, caption, captionedPixels,
                    pending);
            if (pending != null) {
                sourcePixels = null;
            }
            captionedPixels = null;
            return result;
        } finally {
            recyclePixels(reusableSubtitleSources, sourcePixels);
            recyclePixels(reusablePixels, captionedPixels);
        }
    }

    private byte[] renderOrderedSubtitle(Frame frame, InfoFrame info, FrameSubtitler.Caption caption) throws Exception {
        processFrame(frame, info, firstSubtitleProcessor, frameProcessors.size(), caption);
        return copyPixels(frame);
    }

    /** Renders a cue which arrived after decode without seeking or doing effect work on JavaFX. */
    private void renderLateSubtitle(PlaybackCommand.RenderSubtitle render) {
        try {
            PreparedFrame frame = render.frame();
            if (frame.playbackGeneration() != playbackGeneration) {
                return;
            }
            var source = frame.pendingSubtitle();
            var caption = captionRasterizer.prepareCaption(render.text(), frame.width(), frame.height());
            if (caption != null) {
                try (Frame image = source.wrap(frame.width(), frame.height(), render.sourcePixels())) {
                    byte[] pixels = renderOrderedSubtitle(image, source.info, caption);
                    playbackEvents.offer(new PlaybackEvent.SubtitleRendered(frame, render.text(), pixels));
                }
            }
        } catch (Exception e) {
            System.err.println("Could not render subtitles for the displayed frame: " + e.getMessage());
        } finally {
            recyclePixels(reusableSubtitleSources, render.sourcePixels());
        }
    }

    /** A3 NOTE: Copies padded native BGR pixels into owned BGRA bytes before the decoder reuses its frame buffer. */
    private byte[] copyPixels(Frame frame) {
        long conversionStartedNs = playbackProfiler.enabled() ? System.nanoTime() : 0;
        int rowBytes = Math.multiplyExact(frame.imageWidth, DISPLAY_BYTES_PER_PIXEL);
        int pixelBytes = Math.multiplyExact(rowBytes, frame.imageHeight);
        byte[] pixels = borrowPixels(reusablePixels, pixelBytes);

        // FFmpeg supplies padded BGR rows. Pack the pixels with an opaque alpha byte for JavaFX.
        ByteBuffer source = (ByteBuffer) frame.image[0];
        int destination = 0;
        for (int y = 0; y < frame.imageHeight; y++) {
            int index = y * frame.imageStride;
            for (int x = 0; x < frame.imageWidth; x++) {
                pixels[destination++] = source.get(index++);
                pixels[destination++] = source.get(index++);
                pixels[destination++] = source.get(index++);
                pixels[destination++] = (byte) 0xff;
            }
        }
        if (playbackProfiler.enabled()) {
            playbackProfiler.recordConversion(System.nanoTime() - conversionStartedNs);
        }
        return pixels;
    }

    private static byte[] borrowPixels(BlockingQueue<byte[]> pool, int size) {
        byte[] pixels = pool.poll();
        return pixels != null && pixels.length == size ? pixels : new byte[size];
    }

    private static void recyclePixels(BlockingQueue<byte[]> pool, byte[] pixels) {
        if (pixels != null) {
            pool.offer(pixels);
        }
    }

    private void releasePreparedFrame(PreparedFrame frame) {
        reusablePixels.offer(frame.pixels());
        recyclePixels(reusablePixels, frame.captionedPixels());
        if (frame.pendingSubtitle() != null) {
            recyclePixels(reusableSubtitleSources, frame.pendingSubtitle().takePixels());
        }
    }

    /** A3 NOTE: Returns discarded queued pixel arrays to the pool when a seek or stop invalidates them. */
    private void discardPreparedFrames() {
        PreparedFrame frame;
        while ((frame = preparedFrames.poll()) != null) {
            releasePreparedFrame(frame);
        }
    }

    /**
     * Runs on JavaFX without waiting for decoding: drain events, discard stale generations,
     * and display only the newest prepared frame currently due.
     */
    private void pumpPlayback(long now) {
        drainPlaybackEvents();
        if (pausedSnapshot) {
            refreshSubtitle();
            return;
        }

        PreparedFrame latestDueFrame = null;
        while (true) {
            PreparedFrame nextFrame = preparedFrames.peek();
            if (nextFrame == null) {
                break;
            }
            boolean stale = nextFrame.playbackGeneration() != activePlaybackGeneration.get();
            if (!stale && nextFrame.targetTimeNs() > now) {
                break;
            }
            // A seek may have replaced the queue since peek. Only take the frame we inspected.
            if (!preparedFrames.remove(nextFrame)) {
                continue;
            }
            if (stale) {
                releasePreparedFrame(nextFrame);
            } else {
                if (latestDueFrame != null) {
                    releasePreparedFrame(latestDueFrame);
                }
                latestDueFrame = nextFrame;
            }
        }

        if (latestDueFrame != null) {
            displayPreparedFrame(latestDueFrame);
        }
        refreshSubtitle();
    }

    /** Executes worker notifications here so every view callback remains on the JavaFX application thread. */
    private void drainPlaybackEvents() {
        PlaybackEvent event;
        while ((event = playbackEvents.poll()) != null) {
            if (event instanceof PlaybackEvent.MediaOpened media) {
                displayedDurationUs.set(media.durationUs());
                videoSizeChangedHandler.accept(media.width(), media.height());
                if (waitingForSubtitles && subtitleWaitDeadlineNs == 0) {
                    subtitleWaitDeadlineNs = System.nanoTime() + INITIAL_SUBTITLE_WAIT_NS;
                }
            } else if (event instanceof PlaybackEvent.StatusChanged statusChanged) {
                statusChangedHandler.accept(statusChanged.status());
            } else if (event instanceof PlaybackEvent.PlaybackStateChanged stateChanged) {
                pausedSnapshot = stateChanged.paused();
                playbackStateChangedHandler.accept(stateChanged.playing(), stateChanged.paused());
            } else if (event instanceof PlaybackEvent.AudioOutputStateChanged audioStateChanged) {
                audioOutputStateChangedHandler.accept(audioStateChanged.enabled());
            } else if (event instanceof PlaybackEvent.PausedFrameReady preview) {
                displayPreparedFrame(preview.frame());
            } else if (event instanceof PlaybackEvent.SubtitleRendered rendered) {
                if (rendered.frame() == displayedFrame
                        && rendered.frame().playbackGeneration() == activePlaybackGeneration.get()) {
                    lateSubtitle = rendered;
                } else {
                    reusablePixels.offer(rendered.pixels());
                }
            } else if (event == PlaybackEvent.FrameCleared.INSTANCE) {
                displayedMediaTimestampUs.set(NO_SEEK_REQUEST);
                releaseDisplayedFrame();
                paintedCaption = null;
                displayImage = null;
                frameReadyHandler.accept(null);
            } else if (event == PlaybackEvent.ShutdownComplete.INSTANCE) {
                releaseDisplayedFrame();
                playbackTimer.stop();
            }
        }
    }

    /** Retains only the visible frame and selects its current CC version on JavaFX. */
    private void displayPreparedFrame(PreparedFrame frame) {
        boolean retained = false;
        try {
            if (frame.playbackGeneration() != activePlaybackGeneration.get()) {
                return;
            }
            if (displayImage == null || displayImageGeneration != frame.playbackGeneration()
                    || displayImage.getWidth() != frame.width() || displayImage.getHeight() != frame.height()) {
                displayImage = new WritableImage(frame.width(), frame.height());
                displayImageGeneration = frame.playbackGeneration();
            }
            releaseDisplayedFrame();
            displayedFrame = frame;
            retained = true;
        } finally {
            if (!retained) {
                releasePreparedFrame(frame);
            }
        }
        displayedTimestampUs.set(frame.logicalTimestampUs());
        displayedMediaTimestampUs.set(frame.mediaTimestampUs());
        subtitleLoader.moveTo(frame.mediaTimestampUs());
        relativeSeekBaseUs.set(NO_SEEK_REQUEST);

        updateSubtitlePixels(true);
        frameReadyHandler.accept(displayImage);
    }

    private void releaseDisplayedFrame() {
        if (displayedFrame != null) {
            releasePreparedFrame(displayedFrame);
            displayedFrame = null;
        }
        if (lateSubtitle != null) {
            reusablePixels.offer(lateSubtitle.pixels());
            lateSubtitle = null;
        }
    }

    private void updateSubtitlePixels(boolean newFrame) {
        PreparedFrame frame = displayedFrame;
        String text = "";
        if (frame != null) {
            if (subtitlesEnabled.get() && frame.subtitleVersion() == requestedSubtitleVersion) {
                text = subtitleLoader.textAt(frame.mediaTimestampUs());
            }
            if (effectsFollowSubtitles) {
                text = displayOrderedSubtitle(frame, text, newFrame);
            } else {
                displayFinalSubtitle(frame, text, newFrame);
            }
        }
        if (!displayedSubtitleText.equals(text)) {
            displayedSubtitleText = text;
            subtitleTextChangedHandler.accept(text);
        }
    }

    private String displayOrderedSubtitle(PreparedFrame frame, String text, boolean newFrame) {
        byte[] captionedPixels = null;
        if (!text.isEmpty()) {
            if (frame.captionedPixels() != null && frame.caption().text().equals(text)) {
                captionedPixels = frame.captionedPixels();
            } else if (lateSubtitle != null && lateSubtitle.text().equals(text)) {
                captionedPixels = lateSubtitle.pixels();
            } else if (frame.pendingSubtitle() != null && !shutdownRequested
                    && frame.playbackGeneration() == activePlaybackGeneration.get()) {
                byte[] sourcePixels = frame.pendingSubtitle().takePixels();
                if (sourcePixels != null) {
                    enqueueCommand(new PlaybackCommand.RenderSubtitle(frame, text, sourcePixels));
                }
            }
        }
        String visibleText = captionedPixels == null ? "" : text;
        if (newFrame || !displayedSubtitleText.equals(visibleText)) {
            writeDisplayPixels(frame, captionedPixels != null ? captionedPixels : frame.pixels());
        }
        return visibleText;
    }

    private void displayFinalSubtitle(PreparedFrame frame, String text, boolean newFrame) {
        if (!newFrame && displayedSubtitleText.equals(text)) {
            return;
        }
        int stride = frame.width() * DISPLAY_BYTES_PER_PIXEL;
        var writer = displayImage.getPixelWriter();
        if (newFrame) {
            writeDisplayPixels(frame, frame.pixels());
        } else if (paintedCaption != null) {
            var previous = paintedCaption;
            int source = previous.y() * stride + previous.x() * DISPLAY_BYTES_PER_PIXEL;
            writer.setPixels(previous.x(), previous.y(), previous.width(), previous.height(),
                    PixelFormat.getByteBgraPreInstance(), frame.pixels(), source, stride);
        }
        var caption = text.isEmpty() ? null : captionForDisplay(frame, text);
        if (caption != null) {
            int captionStride = caption.width() * DISPLAY_BYTES_PER_PIXEL;
            int required = captionStride * caption.height();
            if (subtitlePixels.length < required) {
                subtitlePixels = new byte[required];
            }
            int layers = frameProcessors.size() - firstSubtitleProcessor;
            caption.composite(frame.pixels(), stride, subtitlePixels, layers);
            writer.setPixels(caption.x(), caption.y(), caption.width(), caption.height(),
                    PixelFormat.getByteBgraPreInstance(), subtitlePixels, 0, captionStride);
        }
        paintedCaption = caption;
    }

    private void writeDisplayPixels(PreparedFrame frame, byte[] pixels) {
        displayImage.getPixelWriter().setPixels(0, 0, frame.width(), frame.height(),
                PixelFormat.getByteBgraPreInstance(), pixels, 0, frame.width() * DISPLAY_BYTES_PER_PIXEL);
    }

    private FrameSubtitler.Caption captionForDisplay(PreparedFrame frame, String text) {
        var caption = frame.caption();
        if (caption != null && caption.matches(text, frame.width(), frame.height())) {
            return caption;
        }
        if (paintedCaption != null && paintedCaption.matches(text, frame.width(), frame.height())) {
            return paintedCaption;
        }
        // A cue can arrive after this frame was prepared, including while the video is paused.
        return FrameSubtitler.rasterize(text, frame.width(), frame.height());
    }

    /** Refreshes captions even while paused and ends initial waiting on completion, failure or timeout. */
    private void refreshSubtitle() {
        updateSubtitlePixels(false);
        SubtitleLoader.Progress progress = subtitleLoader.progress();
        if (!progress.equals(subtitleProgress.get())) {
            subtitleProgress.set(progress);
        }
        if (progress.state() == SubtitleLoader.State.READY || progress.state() == SubtitleLoader.State.NO_AUDIO
                || progress.state() == SubtitleLoader.State.FAILED
                || subtitleWaitDeadlineNs != 0 && System.nanoTime() - subtitleWaitDeadlineNs >= 0) {
            resumeAfterSubtitlePreparation();
        }
    }

    /** A3 NOTE: Sends a version-tagged resume so a late completion cannot resume another video. */
    private void resumeAfterSubtitlePreparation() {
        if (waitingForSubtitles) {
            waitingForSubtitles = false;
            subtitleWaitDeadlineNs = 0;
            enqueueCommand(new PlaybackCommand.ResumePrepared(requestedSubtitleVersion));
        }
    }

    private void queueAudio(long timestampUs, Frame frame) {
        if (!audioOutputEnabled || !audioAvailable) {
            return;
        }

        long copyStartedNs = playbackProfiler.enabled() ? System.nanoTime() : 0;
        byte[] samples = AudioPlayer.copySamples(frame);
        if (samples.length > 0) {
            AudioPacket packet = new AudioPacket(playbackGeneration, timestampUs, samples);
            if (!audioPackets.offer(packet)) {
                // A saturated output queue means sound is already behind the shared clock.
                // Drop the oldest packet so a brief device stall does not create growing latency.
                audioPackets.poll();
                audioPackets.offer(packet);
            }
        }
        if (playbackProfiler.enabled()) {
            playbackProfiler.recordAudioCopy(System.nanoTime() - copyStartedNs);
        }
    }

    /** Owns AudioPlayer and writes only PCM packets whose timestamps are due on the shared playback clock. */
    private void runAudioOutputWorker() {
        AudioOutputState state = new AudioOutputState();
        try {
            while (!state.shutdownRequested) {
                processAudioOutputCommands(state);
                if (state.shutdownRequested) {
                    break;
                }

                AudioClock clock = audioClock.get();
                boolean matchingOutput = state.player != null
                        && state.playbackGeneration == clock.playbackGeneration();
                if (matchingOutput) {
                    // A3 NOTE: Stop/start the matching audio device without discarding buffered samples during pause.
                    state.player.setPaused(clock.paused());
                }
                boolean outputReady = matchingOutput
                        && clock.enabled()
                        && !clock.paused()
                        && clock.firstTimestampUs() != NO_SEEK_REQUEST
                        && clock.playbackStartNs() > 0;

                if (!outputReady) {
                    if (!matchingOutput || !clock.enabled()) {
                        state.pending = null;
                    }
                    if (matchingOutput && !clock.paused() && finishAudioDrainIfReady(state)) {
                        continue;
                    }
                    TimeUnit.MILLISECONDS.sleep(5);
                    continue;
                }

                if (state.pending == null) {
                    AudioPacket packet = audioPackets.poll(5, TimeUnit.MILLISECONDS);
                    if (packet == null) {
                        finishAudioDrainIfReady(state);
                        continue;
                    }
                    if (packet.playbackGeneration() != state.playbackGeneration) {
                        continue;
                    }
                    state.pending = new PendingAudio(
                            packet.playbackGeneration(),
                            packet.timestampUs(),
                            packet.copyPcmSamples());
                }

                clock = audioClock.get();
                if (state.pending.playbackGeneration() != state.playbackGeneration
                        || clock.playbackGeneration() != state.playbackGeneration
                        || !clock.enabled()) {
                    state.pending = null;
                    continue;
                }
                if (clock.paused() || clock.playbackStartNs() <= 0) {
                    continue;
                }

                // Use the same monotonic playback anchor as video and keep audio a small amount ahead of display.
                long dueTimestampUs = clock.firstTimestampUs()
                        + (System.nanoTime() + AUDIO_LEAD_NS - clock.playbackStartNs()) / 1_000L;
                if (state.pending.timestampUs() > dueTimestampUs) {
                    TimeUnit.MILLISECONDS.sleep(2);
                    continue;
                }

                int written = state.player.write(
                        state.pending.samples(),
                        state.pending.offset(),
                        state.pending.remaining());
                if (written > 0) {
                    state.pending.advance(written);
                    if (state.pending.finished()) {
                        state.pending = null;
                        finishAudioDrainIfReady(state);
                    }
                } else {
                    TimeUnit.MILLISECONDS.sleep(2);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            reportAudioFailure(state, e);
        } finally {
            closeAudioOutput(state);
            // An audio-device failure must not stop JavaFX's frame timer. This event is
            // reserved for an intentional application shutdown.
            if (shutdownRequested || shutdownCommandQueued.get() || state.shutdownRequested) {
                playbackEvents.offer(PlaybackEvent.ShutdownComplete.INSTANCE);
            }
        }
    }

    /** Runs only on audioOutputWorker, which is the sole owner of AudioPlayer and its device line. */
    private void processAudioOutputCommands(AudioOutputState state) throws InterruptedException {
        AudioOutputCommand command;
        while ((command = audioOutputCommands.poll()) != null) {
            if (command == AudioOutputCommand.Shutdown.INSTANCE) {
                state.shutdownRequested = true;
                return;
            }

            try {
                if (command instanceof AudioOutputCommand.Open open) {
                    closeAudioOutput(state);
                    state.playbackGeneration = open.playbackGeneration();
                    state.player = new AudioPlayer();
                    state.player.open(open.sampleRate(), open.channels());
                    state.pending = null;
                    state.drainAndClose = false;
                } else if (command instanceof AudioOutputCommand.Reset reset) {
                    state.pending = null;
                    state.drainAndClose = false;
                    state.playbackGeneration = reset.playbackGeneration();
                    if (state.player != null) {
                        state.player.flush();
                    }
                } else if (command instanceof AudioOutputCommand.Flush flush) {
                    if (state.playbackGeneration == flush.playbackGeneration()) {
                        state.pending = null;
                        if (state.player != null) {
                            state.player.flush();
                        }
                    }
                } else if (command instanceof AudioOutputCommand.Close) {
                    state.pending = null;
                    state.drainAndClose = false;
                    closeAudioOutput(state);
                } else if (command instanceof AudioOutputCommand.DrainAndClose drain) {
                    if (state.playbackGeneration == drain.playbackGeneration()) {
                        state.drainAndClose = true;
                        state.drainGeneration = drain.playbackGeneration();
                    }
                }
            } catch (Exception e) {
                reportAudioFailure(state, e);
            }
        }
    }

    /** Closes after both the pending partial packet and the shared queue have been fully consumed. */
    private boolean finishAudioDrainIfReady(AudioOutputState state) {
        if (!state.drainAndClose
                || state.player == null
                || state.playbackGeneration != state.drainGeneration
                || state.pending != null
                || !audioPackets.isEmpty()) {
            return false;
        }

        try {
            state.player.drain();
        } catch (Exception e) {
            reportAudioFailure(state, e);
            return true;
        }
        closeAudioOutput(state);
        state.drainAndClose = false;
        return true;
    }

    /** Returns device failures to playbackWorker as a generation-tagged command instead of touching the UI here. */
    private void reportAudioFailure(AudioOutputState state, Exception error) {
        long failedGeneration = state.playbackGeneration;
        closeAudioOutput(state);
        state.pending = null;
        state.drainAndClose = false;
        if (failedGeneration >= 0) {
            playbackCommands.offer(new PlaybackCommand.AudioFailure(
                    failedGeneration,
                    error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
        }
    }

    /** Releases the audio line on its owning worker and invalidates that worker's active generation. */
    private void closeAudioOutput(AudioOutputState state) {
        if (state.player != null) {
            try {
                state.player.close();
            } catch (Exception e) {
                // The output line is being discarded; continue releasing the other playback resources.
            }
            state.player = null;
        }
        state.playbackGeneration = -1;
    }

    /** Publishes an immutable timing snapshot so audio sees one consistent generation and pause state. */
    private void publishAudioClock() {
        audioClock.set(new AudioClock(
                playbackGeneration,
                firstTimestampUs,
                playbackStartNs,
                pauseRequested || playbackStartNs <= 0,
                audioOutputEnabled && audioAvailable));
    }

    /** Ignores stale device errors and disables audio only for the playback generation that failed. */
    private void handleAudioFailure(PlaybackCommand.AudioFailure failure) {
        if (failure.playbackGeneration() != playbackGeneration) {
            return;
        }
        audioOutputEnabled = false;
        audioOutputEnabledSnapshot = false;
        audioOutputOpenRequested = false;
        audioPackets.clear();
        publishAudioClock();
        audioOutputCommands.offer(new AudioOutputCommand.Close(playbackGeneration));
        notifyAudioOutputStateChanged();
        notifyStatusChanged("Audio unavailable: " + failure.message());
    }

    private void finishPlayback() {
        long endTimestampUs = videoDurationUs != NO_SEEK_REQUEST
                ? videoDurationUs
                : currentTimestampUs;

        // Decoding has ended, but already prepared frames still need to reach JavaFX.
        // Audio can drain only if a running playback clock has already been established.
        // A3 NOTE: Only drain queued audio after a running clock exists; a paused startup must not wait for playback.
        boolean drainAudio = !pauseRequested && firstTimestampUs != NO_SEEK_REQUEST;
        pauseRequested = false;
        closePlaybackResources(drainAudio);
        currentTimestampUs = endTimestampUs;
        displayedTimestampUs.set(endTimestampUs);
        relativeSeekBaseUs.set(endTimestampUs);
        notifyStatusChanged("Playback finished");
        notifyPlaybackStateChanged();
    }

    /** Invalidates queued output before publishing the error state to JavaFX. */
    private void handlePlaybackError(Exception e) {
        if (mediaReadInterrupted()) {
            interruptPlayback();
            return;
        }
        e.printStackTrace();
        abortPlayback("Error: " + e.getMessage(), true);
    }

    private boolean mediaReadInterrupted() {
        try {
            if (Files.size(videoFile.toPath()) < openedMediaSize) {
                return true;
            }
        } catch (IOException | SecurityException e) {
            return true;
        }
        var format = grabber == null ? null : grabber.getFormatContext();
        var input = format == null || format.isNull() ? null : format.pb();
        return input != null && !input.isNull() && input.error() < 0 && input.error() != AVERROR_EOF();
    }

    private void interruptPlayback() {
        currentTimestampUs = displayedTimestampUs.get();
        relativeSeekBaseUs.set(currentTimestampUs);
        abortPlayback("Media unavailable. Reconnect the drive or open another video.", false);
    }

    private void abortPlayback(String status, boolean clearFrame) {
        subtitleLoader.mediaFailed(subtitleVersion);
        playbackGeneration++;
        activePlaybackGeneration.set(playbackGeneration);
        closePlaybackResources();
        pauseRequested = false;
        if (clearFrame) {
            notifyFrameCleared();
        }
        notifyStatusChanged(status);
        notifyPlaybackStateChanged();
    }

    private void closePlaybackResources() {
        closePlaybackResources(false);
    }

    /** Called only by playbackWorker; the audio device is closed separately by audioOutputWorker. */
    private void closePlaybackResources(boolean drainAudio) {
        playbackOpen = false;
        if (!drainAudio) {
            discardPreparedFrames();
            audioPackets.clear();
            audioClock.set(new AudioClock(playbackGeneration, NO_SEEK_REQUEST, 0, true, false));
            if (audioOutputOpenRequested) {
                audioOutputCommands.offer(new AudioOutputCommand.Close(playbackGeneration));
            }
        } else if (audioOutputOpenRequested) {
            audioOutputCommands.offer(new AudioOutputCommand.DrainAndClose(playbackGeneration));
        }
        audioOutputOpenRequested = false;
        firstTimestampUs = NO_SEEK_REQUEST;
        playbackStartNs = 0;
        pauseStartedNs = 0;
        audioAvailable = false;
        frameProcessorsInitialised = false;
        openedMediaSize = -1;

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

    /** Each stage completes before the next reads its pixels. */
    private void processFrame(Frame frame, InfoFrame info, int start, int end, FrameSubtitler.Caption caption)
            throws Exception {
        for (int index = start; index < end; index++) {
            FrameProcessor processor = frameProcessors.get(index);
            if (processor instanceof FrameSubtitler subtitler) {
                if (caption == null) {
                    continue;
                }
                subtitler.setCaption(caption);
            }
            long processorStartedNs = playbackProfiler.enabled() ? System.nanoTime() : 0;
            processor.process(frame, info);
            if (playbackProfiler.enabled()) {
                playbackProfiler.recordProcessor(index, System.nanoTime() - processorStartedNs);
            }
        }
    }

    /** Initialises every selected processor once per video, retaining the command-line effect order. */
    private void initialiseFrameProcessors(InfoVideo videoInfo) throws Exception {
        for (FrameProcessor processor : frameProcessors) {
            processor.initialise(videoInfo);
        }
    }

    private Frame grabFrame() throws Exception {
        if (audioAvailable) {
            return grabber.grab();
        }
        return grabber.grabImage();
    }

    private void notifyFrameCleared() {
        playbackEvents.offer(PlaybackEvent.FrameCleared.INSTANCE);
    }

    private void notifyStatusChanged(String status) {
        playbackEvents.offer(new PlaybackEvent.StatusChanged(status));
    }

    private void notifyPlaybackStateChanged() {
        playbackEvents.offer(new PlaybackEvent.PlaybackStateChanged(playbackOpen, pauseRequested));
    }

    private void notifyAudioOutputStateChanged() {
        playbackEvents.offer(new PlaybackEvent.AudioOutputStateChanged(audioOutputEnabled));
    }

    /** Immutable playback-time snapshot shared with audioOutputWorker through an AtomicReference. */
    private record AudioClock(
            long playbackGeneration,
            long firstTimestampUs,
            long playbackStartNs,
            boolean paused,
            boolean enabled) {
    }

    /** Mutable device state kept local to audioOutputWorker, so it needs no cross-thread locks. */
    private static class AudioOutputState {
        private AudioPlayer player;
        private long playbackGeneration = -1;
        private PendingAudio pending;
        private boolean drainAndClose;
        private long drainGeneration = -1;
        private boolean shutdownRequested;
    }

    /** Tracks partial non-blocking writes for one timestamped PCM packet on audioOutputWorker. */
    private static class PendingAudio {
        private final long playbackGeneration;
        private final long timestampUs;
        private final byte[] samples;
        private int offset;

        PendingAudio(long playbackGeneration, long timestampUs, byte[] samples) {
            this.playbackGeneration = playbackGeneration;
            this.timestampUs = timestampUs;
            this.samples = samples;
        }

        long playbackGeneration() {
            return playbackGeneration;
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
