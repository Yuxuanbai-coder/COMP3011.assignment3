package comp3011;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Prepares the entire video's subtitle track in bounded jobs. Developed with OpenAI Codex assistance. */
public class SubtitleLoader {
    private static final long WINDOW_US = 20_000_000L;
    private static final long CONTEXT_US = 3_000_000L;
    private static final int WORKER_COUNT = 3;
    private static final long UNKNOWN_DURATION = -1;
    private static final long PREPARE_AUDIO = -1;
    private static final int MAX_ATTEMPTS = 3;
    private static final int REQUEST_TIMEOUT_SECONDS = 32;

    private final SubtitleClient client;
    private final SubtitleCache cache;
    private final Map<Long, List<SubtitleSegment>> completed = new HashMap<>();
    private final Map<Long, Failure> failures = new HashMap<>();
    private final Map<Long, Request> activeRequests = new HashMap<>();
    private final Set<Path> obsoleteAudio = new HashSet<>();
    private Path decodedAudio;
    private AudioFingerprint fingerprint;
    private int failedWindows;

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
        this(new SubtitleClient(), new SubtitleCache(Path.of(".a3-subtitle-cache", "v1")));
    }

    SubtitleLoader(SubtitleClient client) {
        this(client, null);
    }

    SubtitleLoader(SubtitleClient client, SubtitleCache cache) {
        this.client = client;
        this.cache = cache;
    }

    public synchronized long setVideo(File file) {
        if (closed) {
            return videoVersion;
        }
        discardAudio();
        fingerprint = null;
        videoFile = file;
        videoVersion++;
        positionUs = 0;
        durationUs = 0;
        mediaOpened = false;
        hasAudio = false;
        failed = false;
        completed.clear();
        failures.clear();
        failedWindows = 0;
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
            failedWindows = 0;
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
        long total = totalWindows();
        if (failed) {
            return new Progress(State.FAILED, completed.size(), total);
        }
        if (mediaOpened && !hasAudio) {
            return new Progress(State.NO_AUDIO, 0, 0);
        }
        if (total > 0 && completed.size() + failedWindows == total) {
            return new Progress(failedWindows == 0 ? State.READY : State.FAILED, completed.size(), total);
        }
        return new Progress(failures.isEmpty() ? State.LOADING : State.RETRYING, completed.size(), total);
    }

    private long totalWindows() {
        return hasAudio && durationUs > 0 ? (durationUs - 1) / WINDOW_US + 1 : 0;
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
                        removeUnusedAudio(request.audio());
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
                if (decodedAudio == null) {
                    if (!activeRequests.containsKey(PREPARE_AUDIO)) {
                        return reserve(PREPARE_AUDIO);
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
        Failure failure = failures.get(startUs);
        return startUs < durationUs && (failure == null || !failure.terminal())
                && !completed.containsKey(startUs) && !activeRequests.containsKey(startUs);
    }

    private long retryDelay(long startUs, long nowNs) {
        Failure failure = failures.get(startUs);
        return failure == null ? 0 : failure.retryAtNs() - nowNs;
    }

    private Request reserve(long startUs) {
        Request request = new Request(videoFile, videoVersion, startUs, durationUs, decodedAudio, Thread.currentThread());
        activeRequests.put(startUs, request);
        return request;
    }

    private void load(Request request) throws InterruptedException {
        long startedNs = System.nanoTime();
        Path directory = null;
        Path audioFile = null;
        try {
            if (request.startUs() == PREPARE_AUDIO) {
                prepareAudio(request);
                return;
            }
            directory = Files.createTempDirectory("a3-subtitle-");
            audioFile = directory.resolve("audio.wav");
            long clipStartUs = Math.max(0, request.startUs() - CONTEXT_US);
            long windowEndUs = Math.min(request.durationUs(), request.startUs() + WINDOW_US);
            long clipEndUs = Math.min(request.durationUs(), windowEndUs + CONTEXT_US);
            Optional<AudioExtractor.SubtitleAudio> extracted = AudioExtractor.writeSubtitleChunk(
                    request.audio(), audioFile, clipStartUs, clipEndUs - clipStartUs);
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
                    var retry = AudioExtractor.writeSubtitleChunk(
                            request.audio(), audioFile, retryStartUs, clipEndUs - retryStartUs);
                    if (!canSubmit(request)) {
                        return;
                    }
                    if (retry.isPresent()) {
                        segments = transcribe(audioFile, retry.get(), request.startUs(), windowEndUs);
                    }
                }
            }
            long playheadUs;
            CacheEntry toCache = null;
            synchronized (this) {
                if (!isCurrent(request)) {
                    return;
                }
                completed.put(request.startUs(), segments);
                failures.remove(request.startUs());
                playheadUs = positionUs;
                if (fingerprint != null && completed.size() == totalWindows()) {
                    var cues = completed.entrySet().stream().sorted(Map.Entry.comparingByKey())
                            .flatMap(entry -> entry.getValue().stream()).toList();
                    toCache = new CacheEntry(fingerprint, cues);
                }
            }
            if (toCache != null) {
                cache.save(toCache.fingerprint(), toCache.cues());
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
        } catch (TimeoutException e) {
            recordFailure(request, e, true);
        } catch (Exception e) {
            recordFailure(request, e, false);
        } finally {
            deleteTemporaryPath(audioFile);
            deleteTemporaryPath(directory);
        }
    }

    private void prepareAudio(Request request) throws Exception {
        Path directory = Files.createTempDirectory("a3-subtitle-audio-");
        Path pcm = directory.resolve("track.pcm");
        boolean inUse = false;
        try {
            long actualDurationUs = AudioExtractor.decodeSubtitleAudio(request.file().toPath(), pcm, request.durationUs());
            long timelineDurationUs = request.durationUs() == UNKNOWN_DURATION ? actualDurationUs : request.durationUs();
            AudioFingerprint analyzed = null;
            Optional<List<SubtitleSegment>> cachedTrack = Optional.empty();
            if (actualDurationUs > 0 && cache != null) {
                try {
                    analyzed = AudioFingerprint.read(pcm);
                    cachedTrack = cache.find(analyzed, timelineDurationUs);
                } catch (IOException e) {
                    System.err.println("Could not identify subtitle audio for caching: " + e.getMessage());
                }
            }
            synchronized (this) {
                if (!isCurrent(request)) {
                    return;
                }
                durationUs = timelineDurationUs;
                hasAudio = actualDurationUs > 0;
                if (hasAudio) {
                    decodedAudio = pcm;
                    inUse = true;
                    fingerprint = analyzed;
                    cachedTrack.ifPresent(this::applyCachedTrack);
                }
            }
            System.out.printf(Locale.ROOT, "Subtitle audio decoded: %.2fs%n", actualDurationUs / 1_000_000.0);
            if (cachedTrack.isPresent()) {
                System.out.printf("Reused %d cached subtitle cues%n", cachedTrack.get().size());
            }
        } finally {
            if (!inUse) {
                deleteTemporaryPath(pcm);
                deleteTemporaryPath(directory);
            }
        }
    }

    private void applyCachedTrack(List<SubtitleSegment> cues) {
        for (long start = 0; start < durationUs; start += WINDOW_US) {
            completed.put(start, new ArrayList<>());
        }
        for (SubtitleSegment cue : cues) {
            long window = windowStart(Math.round(cue.start() * 1_000_000));
            while (window < durationUs && window / 1_000_000.0 < cue.end()) {
                double start = Math.max(cue.start(), window / 1_000_000.0);
                double end = Math.min(cue.end(), (window + WINDOW_US) / 1_000_000.0);
                if (end > start) {
                    completed.get(window).add(new SubtitleSegment(start, end, cue.text()));
                }
                window += WINDOW_US;
            }
        }
    }

    private synchronized boolean canSubmit(Request request) {
        return isCurrent(request) && enabled && !failed;
    }

    private List<SubtitleSegment> transcribe(
            Path file, AudioExtractor.SubtitleAudio audio, long startUs, long endUs)
            throws IOException, InterruptedException, ExecutionException, TimeoutException {
        return SubtitleFormatter.format(requestSubtitles(file), audio.startUs(), startUs, endUs, audio.activity());
    }

    private List<SubtitleSegment> requestSubtitles(Path audioFile)
            throws IOException, InterruptedException, ExecutionException, TimeoutException {
        CompletableFuture<List<SubtitleSegment>> response = client.requestSubtitles(audioFile);
        try {
            return response.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException | TimeoutException e) {
            response.cancel(true);
            throw e;
        }
    }

    private void recordFailure(Request request, Throwable cause, boolean retryable) {
        synchronized (this) {
            if (!isCurrent(request)) {
                return;
            }
            if (request.startUs() == PREPARE_AUDIO) {
                failed = true;
                retryable = false;
            } else {
                Failure previous = failures.get(request.startUs());
                int attempts = previous == null ? 1 : previous.attempts() + 1;
                retryable = retryable && attempts < MAX_ATTEMPTS;
                long delayNs = TimeUnit.SECONDS.toNanos(1L << attempts);
                failures.put(request.startUs(), new Failure(attempts, System.nanoTime() + delayNs, !retryable));
                if (!retryable) {
                    failedWindows++;
                }
            }
        }
        String stage = request.startUs() == PREPARE_AUDIO ? "while decoding audio"
                : "at " + request.startUs() / 1_000_000L + " seconds";
        String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        System.err.printf("Subtitle loading failed %s: %s%s%n", stage, message, retryable ? " (will retry)" : "");
    }

    private boolean isCurrent(Request request) {
        return !closed && request.version() == videoVersion;
    }

    private static long windowStart(long timestampUs) {
        return timestampUs / WINDOW_US * WINDOW_US;
    }

    private void discardAudio() {
        if (decodedAudio != null) {
            obsoleteAudio.add(decodedAudio);
            removeUnusedAudio(decodedAudio);
            decodedAudio = null;
        }
    }

    private void removeUnusedAudio(Path audio) {
        if (audio != null && obsoleteAudio.contains(audio)
                && activeRequests.values().stream().noneMatch(request -> audio.equals(request.audio()))) {
            obsoleteAudio.remove(audio);
            deleteTemporaryPath(audio);
            deleteTemporaryPath(audio.getParent());
        }
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
        discardAudio();
        activeRequests.values().forEach(request -> request.worker().interrupt());
        notifyAll();
    }

    enum State {
        OFF, LOADING, READY, NO_AUDIO, RETRYING, FAILED
    }

    record Progress(State state, int completed, long total) {
    }

    private record Request(File file, long version, long startUs, long durationUs, Path audio, Thread worker) {
    }

    private record Failure(int attempts, long retryAtNs, boolean terminal) {
    }

    private record CacheEntry(AudioFingerprint fingerprint, List<SubtitleSegment> cues) {
    }
}
