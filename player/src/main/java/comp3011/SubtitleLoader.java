package comp3011;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/** Prepares the entire video's subtitle track in bounded jobs. Developed with OpenAI Codex assistance. */
public class SubtitleLoader {
    private static final long WINDOW_US = 20_000_000L;
    private static final long CONTEXT_US = 3_000_000L;
    private static final int WORKER_COUNT = 3;
    private static final long UNKNOWN_DURATION = -1;
    private static final int MAX_ATTEMPTS = 3;

    private final SubtitleClient client;
    private final Map<Long, List<SubtitleSegment>> completed = new HashMap<>();
    private final Map<Long, Failure> failures = new HashMap<>();
    private final Map<Long, Request> activeRequests = new HashMap<>();

    private boolean workersStarted;
    private File videoFile;
    private long videoVersion;
    private long positionUs;
    private long durationUs;
    private boolean mediaOpened;
    private boolean hasAudio;
    private boolean failed;
    private boolean enabled;
    private boolean closed;

    public SubtitleLoader() {
        this(new SubtitleClient());
    }

    SubtitleLoader(SubtitleClient client) {
        this.client = client;
    }

    public synchronized long setVideo(File file) {
        if (closed) {
            return videoVersion;
        }
        videoFile = file;
        videoVersion++;
        positionUs = 0;
        durationUs = 0;
        mediaOpened = false;
        hasAudio = false;
        failed = false;
        completed.clear();
        failures.clear();
        activeRequests.values().forEach(request -> request.worker().interrupt());
        notifyAll();
        return videoVersion;
    }

    synchronized void mediaOpened(long version, boolean hasAudio, long durationUs) {
        if (!closed && version == videoVersion && !mediaOpened) {
            mediaOpened = true;
            this.hasAudio = hasAudio;
            this.durationUs = durationUs > 0 ? durationUs : UNKNOWN_DURATION;
            notifyAll();
        }
    }

    synchronized void mediaFailed(long version) {
        if (version == videoVersion) {
            failed = true;
            activeRequests.values().forEach(request -> request.worker().interrupt());
            notifyAll();
        }
    }

    public synchronized void moveTo(long timestampUs) {
        long previousWindow = windowStart(positionUs);
        positionUs = Math.max(0, timestampUs);
        if (previousWindow != windowStart(positionUs)) {
            notifyAll();
        }
    }

    public synchronized void setEnabled(boolean enabled) {
        if (closed || this.enabled == enabled) {
            return;
        }
        this.enabled = enabled;
        if (enabled) {
            failures.clear();
            failed = false;
            if (!workersStarted) {
                for (int i = 1; i <= WORKER_COUNT; i++) {
                    Thread.ofPlatform().name("subtitle-loader-" + i).daemon().start(this::run);
                }
                workersStarted = true;
            }
        }
        notifyAll();
    }

    public synchronized String textAt(long timestampUs) {
        return textInWindow(completed.get(windowStart(timestampUs)), timestampUs);
    }

    synchronized Progress progress() {
        if (!enabled || closed || videoFile == null) {
            return new Progress(State.OFF, 0, 0);
        }
        long total = hasAudio && durationUs > 0 ? (durationUs - 1) / WINDOW_US + 1 : 0;
        if (failed) {
            return new Progress(State.FAILED, completed.size(), total);
        }
        if (mediaOpened && !hasAudio) {
            return new Progress(State.NO_AUDIO, 0, 0);
        }
        if (total > 0 && completed.size() == total) {
            return new Progress(State.READY, completed.size(), total);
        }
        return new Progress(failures.isEmpty() ? State.LOADING : State.RETRYING, completed.size(), total);
    }

    private static String textInWindow(List<SubtitleSegment> segments, long timestampUs) {
        if (segments != null) {
            double seconds = timestampUs / 1_000_000.0;
            for (SubtitleSegment segment : segments) {
                if (seconds >= segment.start() && seconds < segment.end()) {
                    return segment.text();
                }
            }
        }
        return "";
    }

    private void run() {
        while (true) {
            Request request = null;
            try {
                request = awaitRequest();
                if (request == null) {
                    return;
                }
                load(request);
            } catch (InterruptedException e) {
                // Recheck the playback position and media version after cancellation.
                Thread.interrupted();
            } finally {
                if (request != null) {
                    synchronized (this) {
                        activeRequests.remove(request.startUs(), request);
                        notifyAll();
                    }
                }
            }
        }
    }

    private synchronized Request awaitRequest() throws InterruptedException {
        while (!closed) {
            long retryDelayNs = Long.MAX_VALUE;
            if (enabled && hasAudio && !failed) {
                if (durationUs == UNKNOWN_DURATION) {
                    if (isPending(0)) {
                        return reserve(0);
                    }
                } else {
                    long nowNs = System.nanoTime();
                    long currentUs = windowStart(positionUs);
                    if (isPending(currentUs) && retryDelay(currentUs, nowNs) <= 0) {
                        return reserve(currentUs);
                    }
                    for (long startUs = 0; startUs < durationUs; startUs += WINDOW_US) {
                        if (isPending(startUs)) {
                            long delayNs = retryDelay(startUs, nowNs);
                            if (delayNs <= 0) {
                                return reserve(startUs);
                            }
                            retryDelayNs = Math.min(retryDelayNs, delayNs);
                        }
                    }
                }
            }
            if (retryDelayNs == Long.MAX_VALUE) {
                wait();
            } else {
                TimeUnit.NANOSECONDS.timedWait(this, retryDelayNs);
            }
        }
        return null;
    }

    private boolean isPending(long startUs) {
        return (durationUs == UNKNOWN_DURATION || startUs < durationUs)
                && !completed.containsKey(startUs) && !activeRequests.containsKey(startUs);
    }

    private long retryDelay(long startUs, long nowNs) {
        Failure failure = failures.get(startUs);
        return failure == null ? 0 : failure.retryAtNs() - nowNs;
    }

    private Request reserve(long startUs) {
        Request request = new Request(videoFile, videoVersion, startUs, durationUs, Thread.currentThread());
        activeRequests.put(startUs, request);
        return request;
    }

    private void load(Request request) throws InterruptedException {
        long startedNs = System.nanoTime();
        Path directory = null;
        Path audioFile = null;
        try {
            if (request.durationUs() == UNKNOWN_DURATION) {
                long audioDurationUs = AudioExtractor.audioDurationUs(request.file().toPath());
                synchronized (this) {
                    if (isCurrent(request)) {
                        durationUs = audioDurationUs;
                        hasAudio = audioDurationUs > 0;
                    }
                }
                return;
            }
            directory = Files.createTempDirectory("a3-subtitle-");
            audioFile = directory.resolve("audio.wav");
            long clipStartUs = Math.max(0, request.startUs() - CONTEXT_US);
            long windowEndUs = Math.min(request.durationUs(), request.startUs() + WINDOW_US);
            long clipEndUs = Math.min(request.durationUs(), windowEndUs + CONTEXT_US);
            Optional<AudioExtractor.SubtitleAudio> extracted = AudioExtractor.extractSubtitleChunk(
                    request.file().toPath(), audioFile, clipStartUs, clipEndUs - clipStartUs);
            if (!canSubmit(request)) {
                return;
            }
            List<SubtitleSegment> segments = List.of();
            if (extracted.isPresent() && extracted.get().activity().containsSignal()) {
                AudioExtractor.SubtitleAudio audio = extracted.get();
                segments = transcribe(audioFile, audio, request.startUs(), windowEndUs);
                long retryStartUs = audio.startUs() + audio.activity().retryAfterQuietLeadUs();
                if (segments.isEmpty() && retryStartUs > clipStartUs && retryStartUs < windowEndUs) {
                    // An active window can come back empty after a long quiet lead. Retry from its first sustained sound.
                    Files.delete(audioFile);
                    var retry = AudioExtractor.extractSubtitleChunk(
                            request.file().toPath(), audioFile, retryStartUs, clipEndUs - retryStartUs);
                    if (!canSubmit(request)) {
                        return;
                    }
                    if (retry.isPresent()) {
                        segments = transcribe(audioFile, retry.get(), request.startUs(), windowEndUs);
                    }
                }
            }
            long playheadUs;
            synchronized (this) {
                if (!isCurrent(request)) {
                    return;
                }
                completed.put(request.startUs(), segments);
                failures.remove(request.startUs());
                playheadUs = positionUs;
            }
            System.out.printf(Locale.ROOT,
                    "Subtitle segments ready: %d (window %ds, playhead %.2fs, loaded in %.2fs)%n",
                    segments.size(), request.startUs() / 1_000_000L, playheadUs / 1_000_000.0,
                    (System.nanoTime() - startedNs) / 1_000_000_000.0);
        } catch (InterruptedException e) {
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            boolean retryable = cause instanceof IOException
                    || cause instanceof SubtitleClient.RequestFailed error && error.retryable();
            recordFailure(request, cause, retryable);
        } catch (Exception e) {
            recordFailure(request, e, false);
        } finally {
            deleteTemporaryPath(audioFile);
            deleteTemporaryPath(directory);
        }
    }

    private synchronized boolean canSubmit(Request request) {
        return isCurrent(request) && enabled && !failed;
    }

    private List<SubtitleSegment> transcribe(
            Path file, AudioExtractor.SubtitleAudio audio, long startUs, long endUs)
            throws IOException, InterruptedException, ExecutionException {
        return SubtitleFormatter.format(requestSubtitles(file), audio.startUs(), startUs, endUs, audio.activity());
    }

    private List<SubtitleSegment> requestSubtitles(Path audioFile)
            throws IOException, InterruptedException, ExecutionException {
        CompletableFuture<List<SubtitleSegment>> response = client.requestSubtitles(audioFile);
        try {
            return response.get();
        } catch (InterruptedException e) {
            response.cancel(true);
            throw e;
        }
    }

    private void recordFailure(Request request, Throwable cause, boolean retryable) {
        synchronized (this) {
            if (!isCurrent(request)) {
                return;
            }
            Failure previous = failures.get(request.startUs());
            int attempts = previous == null ? 1 : previous.attempts() + 1;
            retryable = retryable && attempts < MAX_ATTEMPTS;
            long delayNs = TimeUnit.SECONDS.toNanos(1L << attempts);
            failures.put(request.startUs(), new Failure(attempts, System.nanoTime() + delayNs));
            failed |= !retryable;
        }
        System.err.printf("Subtitle loading failed at %d seconds: %s%s%n",
                request.startUs() / 1_000_000L, cause.getMessage(), retryable ? " (will retry)" : "");
    }

    private boolean isCurrent(Request request) {
        return !closed && request.version() == videoVersion;
    }

    private static long windowStart(long timestampUs) {
        return timestampUs / WINDOW_US * WINDOW_US;
    }

    private static void deleteTemporaryPath(Path path) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                System.err.println("Could not remove temporary subtitle file: " + path);
            }
        }
    }

    public synchronized void close() {
        closed = true;
        completed.clear();
        activeRequests.values().forEach(request -> request.worker().interrupt());
        notifyAll();
    }

    enum State {
        OFF, LOADING, READY, NO_AUDIO, RETRYING, FAILED
    }

    record Progress(State state, int completed, long total) {
    }

    private record Request(File file, long version, long startUs, long durationUs, Thread worker) {
    }

    private record Failure(int attempts, long retryAtNs) {
    }
}
