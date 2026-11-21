package comp3011;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Uploads audio asynchronously to the local subtitle server. Developed with OpenAI Codex assistance. */
public class SubtitleClient {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final URI endpoint;
    private final ObjectMapper mapper = JsonMapper.builder().build();

    /** A3 NOTE: Uses the local service by default for the normal two-process setup. */
    public SubtitleClient() {
        this(URI.create("http://localhost:8080/api/v1/subtitles"));
    }

    /** A3 NOTE: Allows another service address to be supplied without changing the upload code. */
    public SubtitleClient(URI endpoint) {
        this.endpoint = endpoint;
    }

    /** A3 NOTE: Selects the audio MIME type and converts the JSON response to an immutable cue list. */
    public CompletableFuture<List<SubtitleSegment>> requestSubtitles(Path audioFile) throws IOException {
        String name = audioFile.getFileName().toString().toLowerCase(Locale.ROOT);
        CompletableFuture<String> request = switch (name.substring(name.lastIndexOf('.') + 1)) {
            case "flac" -> sendAudio(audioFile, "audio.flac", "audio/flac");
            case "wav" -> sendAudio(audioFile, "audio.wav", "audio/wav");
            default -> sendAudio(audioFile, "audio.m4a", "audio/mp4");
        };
        return request.thenApply(json -> {
            SubtitleResponse response = mapper.readValue(json, SubtitleResponse.class);
            if (response == null || response.segments() == null) {
                throw new IllegalStateException("Missing segments in subtitle response");
            }
            return List.copyOf(response.segments());
        });
    }

    /** A3 NOTE: Streams multipart audio with a unique boundary and bounds how long an HTTP request can wait. */
    private CompletableFuture<String> sendAudio(Path audioFile, String filename, String contentType)
            throws IOException {
        String boundary = "----subtitle-" + UUID.randomUUID();

        String beginning = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; "
                + "filename=\"" + filename + "\"\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n";

        String ending = "\r\n--" + boundary + "--\r\n";

        HttpRequest.BodyPublisher body = HttpRequest.BodyPublishers.concat(
                HttpRequest.BodyPublishers.ofString(
                        beginning, StandardCharsets.UTF_8),
                HttpRequest.BodyPublishers.ofFile(audioFile),
                HttpRequest.BodyPublishers.ofString(
                        ending, StandardCharsets.UTF_8)
        );

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(35))
                .header(
                        "Content-Type",
                        "multipart/form-data; boundary=" + boundary)
                .POST(body)
                .build();

        return httpClient.sendAsync(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        ).thenApply(response -> {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RequestFailed(response.statusCode());
            }

            return response.body();
        });
    }

    /** A3 NOTE: Retains the HTTP status so the loader can distinguish temporary service errors from other failures. */
    static final class RequestFailed extends RuntimeException {
        private final int status;

        RequestFailed(int status) {
            super("Subtitle server returned HTTP " + status);
            this.status = status;
        }

        /** Rate limiting, gateway failures and temporary outages can recover without user intervention. */
        boolean retryable() {
            return status == 429 || status == 502 || status == 503 || status == 504;
        }
    }
}
