package comp3011;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import tools.jackson.databind.json.JsonMapper;

/** Persists complete subtitle tracks and the fingerprints needed to find an excerpt. */
final class SubtitleCache {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final int MAX_EXCERPT_CANDIDATES = 16;
    private final Path directory;

    SubtitleCache(Path directory) {
        this.directory = directory;
    }

    Optional<List<SubtitleSegment>> find(AudioFingerprint fingerprint, long durationUs) throws InterruptedException {
        Path exactPath = path(fingerprint);
        var exact = read(exactPath);
        if (exact != null) {
            return Optional.of(shiftAndTrim(exact.cues(), 0, durationUs));
        }
        if (!Files.isDirectory(directory)) {
            return Optional.empty();
        }

        List<Path> candidates;
        try (var files = Files.list(directory)) {
            candidates = files.filter(path -> !path.equals(exactPath) && path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparingLong(SubtitleCache::lastModified).reversed())
                    .limit(MAX_EXCERPT_CANDIDATES).toList();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
        for (Path candidate : candidates) {
            var track = read(candidate);
            if (track == null) {
                continue;
            }
            var offset = AudioFingerprint.findExcerpt(track.features(), fingerprint.features());
            if (offset.isPresent()) {
                return Optional.of(shiftAndTrim(track.cues(), offset.getAsLong(), durationUs));
            }
        }
        return Optional.empty();
    }

    void save(AudioFingerprint fingerprint, List<SubtitleSegment> cues) {
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            temporary = Files.createTempFile(directory, "subtitles-", ".tmp");
            MAPPER.writeValue(temporary.toFile(), new CachedTrack(fingerprint.features(), cues));
            try {
                Files.move(temporary, path(fingerprint), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, path(fingerprint), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("Could not save subtitle cache: " + e.getMessage());
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException e) {
                    System.err.println("Could not remove temporary subtitle cache: " + e.getMessage());
                }
            }
        }
    }

    private Path path(AudioFingerprint fingerprint) {
        return directory.resolve(fingerprint.digest() + ".json");
    }

    private static CachedTrack read(Path path) {
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            var track = MAPPER.readValue(path.toFile(), CachedTrack.class);
            if (track != null && track.features() != null && track.cues() != null
                    && track.cues().stream().allMatch(SubtitleCache::validCue)) {
                return track;
            }
        } catch (RuntimeException e) {
            // A stale or incomplete file is a cache miss.
        }
        return null;
    }

    private static boolean validCue(SubtitleSegment cue) {
        return cue != null && Double.isFinite(cue.start()) && Double.isFinite(cue.end())
                && cue.start() >= 0 && cue.end() > cue.start() && cue.text() != null;
    }

    private static List<SubtitleSegment> shiftAndTrim(List<SubtitleSegment> cues, long offsetUs, long durationUs) {
        double offset = offsetUs / 1_000_000.0;
        double duration = durationUs / 1_000_000.0;
        List<SubtitleSegment> shifted = new ArrayList<>();
        for (SubtitleSegment cue : cues) {
            double start = Math.max(0, cue.start() - offset);
            double end = Math.min(duration, cue.end() - offset);
            if (end > start) {
                shifted.add(new SubtitleSegment(start, end, cue.text()));
            }
        }
        return List.copyOf(shifted);
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    private record CachedTrack(byte[] features, List<SubtitleSegment> cues) {
    }
}
