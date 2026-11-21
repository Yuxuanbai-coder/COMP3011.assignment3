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

/** A3 NOTE: Prepares all subtitle windows with three workers and bounded retries. Developed with OpenAI Codex assistance. */
public class SubtitleLoader {
    // A3 NOTE: Each 20 s ownership window has 3 s of surrounding audio context; only its own cues are retained.
    private static final long WINDOW_US = 20_000_000L;
    private static final long CONTEXT_US = 3_000_000L;
    private static final int WORKER_COUNT = 3;
    private static final long UNKNOWN_DURATION = -1;
    private static final long PREPARE_AUDIO = -1;
    private static final int MAX_ATTEMPTS = 3;
    private static final int REQUEST_TIMEOUT_SECONDS = 32;

    private final SubtitleClient client;
    private final SubtitleCache cache;
    // A3 NOTE: Mutable scheduling state is guarded by this loader's monitor; decoding, HTTP and cache I/O run outside it.
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

    /** A3 NOTE: Creates the local HTTP client and a versioned, persistent cache under the working directory. */
    public SubtitleLoader() {
        this(new SubtitleClient(), new SubtitleCache(Path.of(".a3-subtitle-cache", "v1")));
    }

    /** A3 NOTE: Tests inject a fake client with caching disabled so no network or persistent files are needed. */
    SubtitleLoader(SubtitleClient client) {
        this(client, null);
    }

    /** A3 NOTE: Injects both dependencies, allowing cache lookup and online requests to be tested independently. */
    SubtitleLoader(SubtitleClient client, SubtitleCache cache) {
        this.client = client;
        this.cache = cache;
    }

    /** A3 NOTE: Advances the video version, clears old cues and interrupts jobs belonging to the previous video. */
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

    /** A3 NOTE: Accepts audio/duration metadata only for the video version that is still selected. */
    synchronized void mediaOpened(long version, boolean hasAudio, long durationUs) {
        if (!closed && version == videoVersion && !mediaOpened) {
            mediaOpened = true;
            this.hasAudio = hasAudio;
            this.durationUs = durationUs > 0 ? durationUs : UNKNOWN_DURATION;
            notifyAll();
        }
    }

    /** A3 NOTE: Stops subtitle jobs when playback cannot open the current media. */
    synchronized void mediaFailed(long version) {
        if (version == videoVersion) {
            failed = true;
            activeRequests.values().forEach(request -> request.worker().interrupt());
            notifyAll();
        }
    }

    /** A3 NOTE: A seek reprioritizes the current window while background workers still prepare the full track. */
    public synchronized void moveTo(long timestampUs) {
        long previousWindow = windowStart(positionUs);
        positionUs = Math.max(0, timestampUs);
        if (previousWindow != windowStart(positionUs)) {
            notifyAll();
        }
    }

    /** A3 NOTE: Starts workers lazily; disabling suspends new jobs, while re-enabling clears earlier failures. */
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

    /** A3 NOTE: Reads already prepared cues only, keeping network and file I/O off the JavaFX display path. */
    public synchronized String textAt(long timestampUs) {
        return textInWindow(completed.get(windowStart(timestampUs)), timestampUs);
    }

    /** A3 NOTE: Produces a small status snapshot for the UI, including silent media and permanently failed windows. */
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

    /** A3 NOTE: Rounds up so a final partial 20 s window is also prepared. */
    private long totalWindows() {
        return hasAudio && durationUs > 0 ? (durationUs - 1) / WINDOW_US + 1 : 0;
    }

    /** A3 NOTE: Cue intervals include their start and exclude their end, preventing an expired line from lingering. */
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

    /** A3 NOTE: Worker lifecycle releases reservations and obsolete PCM files even after cancellation or failure. */
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

    /** A3 NOTE: Reserves audio preparation once, then prioritizes the playhead and waits without busy polling. */
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

    /** A3 NOTE: Avoids submitting duplicate, completed or permanently failed windows while holding the monitor. */
    private boolean isPending(long startUs) {
        Failure failure = failures.get(startUs);
        return startUs < durationUs && (failure == null || !failure.terminal())
                && !completed.containsKey(startUs) && !activeRequests.containsKey(startUs);
    }

    /** A3 NOTE: Uses a monotonic clock for retry deadlines so wall-clock changes do not affect scheduling. */
    private long retryDelay(long startUs, long nowNs) {
        Failure failure = failures.get(startUs);
        return failure == null ? 0 : failure.retryAtNs() - nowNs;
    }

    /** A3 NOTE: Captures file/version/audio ownership and marks the job active before another worker can take it. */
    private Request reserve(long startUs) {
        Request request = new Request(videoFile, videoVersion, startUs, durationUs, decodedAudio, Thread.currentThread());
        activeRequests.put(startUs, request);
        return request;
    }

    /** A3 NOTE: Extracts overlapping WAV context, skips silence and publishes results only for the current video. */
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
                // A3 NOTE: Persist only a fully completed track; partial results must not become a permanent cache hit.
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

    /** A3 NOTE: Decodes one shared PCM file, identifies its audio and checks the cache before scheduling uploads. */
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

    /** A3 NOTE: Repartitions cached cues into loader windows, splitting cues that cross a window boundary. */
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

    /** A3 NOTE: Rechecks selection and caption state after extraction, before an upload can be started. */
    private synchronized boolean canSubmit(Request request) {
        return isCurrent(request) && enabled && !failed;
    }

    /** A3 NOTE: Converts clip-relative results into window-owned, video-relative readable captions. */
    private List<SubtitleSegment> transcribe(
            Path file, AudioExtractor.SubtitleAudio audio, long startUs, long endUs)
            throws IOException, InterruptedException, ExecutionException, TimeoutException {
        return SubtitleFormatter.format(requestSubtitles(file), audio.startUs(), startUs, endUs, audio.activity());
    }

    /** A3 NOTE: Waits only on a subtitle worker and cancels its HTTP future on timeout or interruption. */
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

    /** A3 NOTE: Retries temporary job failures with exponential delay, up to three job attempts per window. */
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

    /** A3 NOTE: A late completion from an old video must never replace the selected video's captions. */
    private boolean isCurrent(Request request) {
        return !closed && request.version() == videoVersion;
    }

    /** A3 NOTE: Maps a media timestamp in microseconds to its 20 s window key. */
    private static long windowStart(long timestampUs) {
        return timestampUs / WINDOW_US * WINDOW_US;
    }

    /** A3 NOTE: Retires the shared PCM file but defers deletion while an existing job still references it. */
    private void discardAudio() {
        if (decodedAudio != null) {
            obsoleteAudio.add(decodedAudio);
            removeUnusedAudio(decodedAudio);
            decodedAudio = null;
        }
    }

    /** A3 NOTE: Deletes retired PCM only after its final active reader releases ownership. */
    private void removeUnusedAudio(Path audio) {
        if (audio != null && obsoleteAudio.contains(audio)
                && activeRequests.values().stream().noneMatch(request -> audio.equals(request.audio()))) {
            obsoleteAudio.remove(audio);
            deleteTemporaryPath(audio);
            deleteTemporaryPath(audio.getParent());
        }
    }

    /** A3 NOTE: Cleanup failures are logged without hiding the original decode/transcription outcome. */
    private static void deleteTemporaryPath(Path path) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                System.err.println("Could not remove temporary subtitle file: " + path);
            }
        }
    }

    /** A3 NOTE: Signals all workers to exit and interrupts pending work without blocking JavaFX shutdown. */
    public synchronized void close() {
        closed = true;
        completed.clear();
        discardAudio();
        activeRequests.values().forEach(request -> request.worker().interrupt());
        notifyAll();
    }

    // A3 NOTE: Explicit states let the view distinguish preparation, retries, silence and incomplete captions.
    enum State {
        OFF, LOADING, READY, NO_AUDIO, RETRYING, FAILED
    }

    /** A3 NOTE: Immutable status passed to the UI; counts refer to windows rather than individual text cues. */
    record Progress(State state, int completed, long total) {
    }

    /** A3 NOTE: Immutable job identity ties cancellation, result validation and PCM ownership to one worker. */
    private record Request(File file, long version, long startUs, long durationUs, Path audio, Thread worker) {
    }

    /** A3 NOTE: Stores the attempt count, monotonic retry deadline and whether this window is exhausted. */
    private record Failure(int attempts, long retryAtNs, boolean terminal) {
    }

    /** A3 NOTE: Snapshots complete cues under the monitor so saving can happen after releasing the lock. */
    private record CacheEntry(AudioFingerprint fingerprint, List<SubtitleSegment> cues) {
    }
}
