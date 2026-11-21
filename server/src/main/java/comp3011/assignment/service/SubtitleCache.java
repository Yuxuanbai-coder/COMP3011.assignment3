package comp3011.assignment.service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import comp3011.assignment.api.SubtitleResponse;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Stores successful subtitle responses by audio content and shares concurrent requests. Developed with OpenAI Codex assistance. */
@Component
final class SubtitleCache {
    private static final Logger logger = LoggerFactory.getLogger(SubtitleCache.class);

    private final Path directory;
    private final JsonMapper mapper;
    private final ConcurrentHashMap<String, CompletableFuture<SubtitleResponse>> inFlight = new ConcurrentHashMap<>();

    SubtitleCache(@Value("${a3.subtitle-cache-directory:.a3-subtitle-server-cache/v1}") String directory,
            JsonMapper mapper) {
        this.directory = Path.of(directory);
        this.mapper = mapper;
    }

    SubtitleResponse getOrTranscribe(MultipartFile audio, Supplier<SubtitleResponse> transcribe) {
        String key;
        try {
            key = digest(audio);
        } catch (IOException e) {
            logger.warn("Could not fingerprint subtitle audio. Sending the request without caching.", e);
            return transcribe.get();
        }

        var pending = new CompletableFuture<SubtitleResponse>();
        var existing = inFlight.putIfAbsent(key, pending);
        if (existing != null) {
            return await(existing);
        }

        try {
            Path entry = directory.resolve(key + ".json");
            SubtitleResponse response = read(entry);
            if (response == null) {
                response = transcribe.get();
                write(entry, response);
            }
            pending.complete(response);
            return response;
        } catch (RuntimeException | Error e) {
            pending.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(key, pending);
        }
    }

    private static SubtitleResponse await(CompletableFuture<SubtitleResponse> response) {
        try {
            return response.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            if (e.getCause() instanceof Error cause) {
                throw cause;
            }
            throw e;
        }
    }

    private SubtitleResponse read(Path entry) {
        try (var input = Files.newInputStream(entry)) {
            SubtitleResponse response = mapper.readValue(input, SubtitleResponse.class);
            return response != null && response.segments() != null ? response : null;
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException | JacksonException e) {
            logger.warn("Could not read a cached subtitle response. Treating it as a cache miss.", e);
            return null;
        }
    }

    private void write(Path entry, SubtitleResponse response) {
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            temporary = Files.createTempFile(directory, "subtitle-", ".tmp");
            try (var output = Files.newOutputStream(temporary)) {
                mapper.writeValue(output, response);
            }
            try {
                Files.move(temporary, entry, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, entry, StandardCopyOption.REPLACE_EXISTING);
            }
            temporary = null;
        } catch (IOException | JacksonException e) {
            logger.warn("Could not save the subtitle response to the server cache.", e);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException e) {
                    logger.warn("Could not remove an incomplete subtitle cache entry.", e);
                }
            }
        }
    }

    private static String digest(MultipartFile audio) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
        try (var input = new DigestInputStream(audio.getInputStream(), digest)) {
            input.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
