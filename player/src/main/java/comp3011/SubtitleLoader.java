package comp3011;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/** Loads the current audio window and prefetches the next one. Developed with OpenAI Codex assistance. */
public class SubtitleLoader {
    private static final long WINDOW_US = 20_000_000L;
    private static final long CONTEXT_US = 1_000_000L;
    private static final long PREFETCH_US = 15_000_000L;

    private final SubtitleClient client;
    private final Map<Long, List<SubtitleSegment>> completed = new HashMap<>();
    private final Map<Long, Failure> failures = new HashMap<>();

    private Thread worker;
    private Request activeRequest;
    private File videoFile;
    private long videoVersion;
    private long positionUs;
    private long durationUs;
    private boolean hasAudio;
    private boolean enabled;
    private boolean closed;

    public SubtitleLoader() {
        this(new SubtitleClient());
    }

    SubtitleLoader(SubtitleClient client) {
        this.client = client;
    }

    public synchronized void setVideo(File file) {
        if (closed) {
            return;
        }
        videoFile = file;
        videoVersion++;
        positionUs = 0;
        durationUs = 0;
        hasAudio = false;
        completed.clear();
        failures.clear();
        if (worker != null) {
            worker.interrupt();
        }
    }

    public synchronized void mediaOpened(File file, boolean hasAudio, long durationUs) {
        if (!closed && file.equals(videoFile)) {
            this.hasAudio = hasAudio;
            this.durationUs = durationUs > 0 ? durationUs : Long.MAX_VALUE;
            notifyAll();
        }
    }

    public synchronized void moveTo(long timestampUs) {
        long previousWindow = windowStart(positionUs);
        boolean wasPrefetching = wantsNextWindow();
        positionUs = Math.max(0, timestampUs);
        long currentWindow = windowStart(positionUs);
        if (previousWindow != currentWindow && activeRequest != null && !wantsWindow(activeRequest.startUs())) {
            worker.interrupt();
        }
        if (previousWindow != currentWindow || wasPrefetching != wantsNextWindow()) {
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
            if (worker == null) {
                worker = Thread.ofPlatform().name("subtitle-loader").daemon().start(this::run);
            }
        }
        notifyAll();
    }

    public synchronized String textAt(long timestampUs) {
        return textInWindow(completed.get(windowStart(timestampUs)), timestampUs);
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
            try {
                Request request = awaitRequest();
                if (request == null) {
                    return;
                }
                load(request);
            } catch (InterruptedException e) {
                // Recheck the playback position and media version after cancellation.
                Thread.interrupted();
            } finally {
                synchronized (this) {
                    activeRequest = null;
                }
            }
        }
    }

    private synchronized Request awaitRequest() throws InterruptedException {
        while (!closed) {
            if (enabled && hasAudio) {
                long startUs = windowStart(positionUs);
                if (completed.containsKey(startUs) && wantsNextWindow()) {
                    startUs += WINDOW_US;
                }
                if (startUs < durationUs && !completed.containsKey(startUs)) {
                    Failure failure = failures.get(startUs);
                    long waitNs = failure == null ? 0 : failure.retryAtNs() - System.nanoTime();
                    if (failure == null || failure.retryable() && waitNs <= 0) {
                        activeRequest = new Request(videoFile, videoVersion, startUs, durationUs);
                        return activeRequest;
                    }
                    if (failure.retryable()) {
                        TimeUnit.NANOSECONDS.timedWait(this, waitNs);
                        continue;
                    }
                }
            }
            wait();
        }
        return null;
    }

    private void load(Request request) throws InterruptedException {
        Path directory = null;
        Path audioFile = null;
        try {
            directory = Files.createTempDirectory("a3-subtitle-");
            audioFile = directory.resolve("audio.flac");
            long clipStartUs = Math.max(0, request.startUs() - CONTEXT_US);
            long windowEndUs = Math.min(request.durationUs(), request.startUs() + WINDOW_US);
            long clipEndUs = Math.min(request.durationUs(), windowEndUs + CONTEXT_US);
            OptionalLong audioStartUs = AudioExtractor.extractSubtitleChunk(
                    request.file().toPath(), audioFile, clipStartUs, clipEndUs - clipStartUs);
            synchronized (this) {
                if (!isCurrent(request) || !enabled || !wantsWindow(request.startUs())) {
                    return;
                }
            }
            List<SubtitleSegment> segments = List.of();
            if (audioStartUs.isPresent()) {
                segments = SubtitleFormatter.format(requestSubtitles(audioFile), audioStartUs.getAsLong(),
                        request.startUs(), windowEndUs);
            }
            synchronized (this) {
                if (isCurrent(request)) {
                    completed.put(request.startUs(), segments);
                    failures.remove(request.startUs());
                    System.out.printf("Subtitle segments ready: %d (%d seconds into video)%n",
                            segments.size(), request.startUs() / 1_000_000L);
                }
            }
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

    private synchronized void recordFailure(Request request, Throwable cause, boolean retryable) {
        if (isCurrent(request)) {
            Failure previous = failures.get(request.startUs());
            int attempts = previous == null ? 1 : Math.min(previous.attempts() + 1, 4);
            long delayNs = TimeUnit.SECONDS.toNanos(Math.min(1L << attempts, 15));
            failures.put(request.startUs(), new Failure(attempts, System.nanoTime() + delayNs, retryable));
            System.err.printf("Subtitle loading failed at %d seconds: %s%s%n",
                    request.startUs() / 1_000_000L, cause.getMessage(), retryable ? " (will retry)" : "");
        }
    }

    private boolean isCurrent(Request request) {
        return !closed && request.version() == videoVersion;
    }

    private boolean wantsNextWindow() {
        return positionUs % WINDOW_US >= WINDOW_US - PREFETCH_US;
    }

    private boolean wantsWindow(long startUs) {
        long currentWindow = windowStart(positionUs);
        return startUs == currentWindow || startUs == currentWindow + WINDOW_US
                && completed.containsKey(currentWindow) && wantsNextWindow();
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
        if (worker != null) {
            worker.interrupt();
        }
    }

    private record Request(File file, long version, long startUs, long durationUs) {
    }

    private record Failure(int attempts, long retryAtNs, boolean retryable) {
    }
}
