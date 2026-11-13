package comp3011;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class SubtitleClient {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final URI endpoint;

    public SubtitleClient() {
        this(URI.create("http://localhost:8080/api/v1/subtitles"));
    }

    public SubtitleClient(URI endpoint) {
        this.endpoint = endpoint;
    }

    public CompletableFuture<String> sendM4a(Path audioFile) throws IOException {
        String boundary = "----subtitle-" + UUID.randomUUID();

        String beginning = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; "
                + "filename=\"audio.m4a\"\r\n"
                + "Content-Type: audio/mp4\r\n\r\n";

        String ending = "\r\n--" + boundary + "--\r\n";

        HttpRequest.BodyPublisher body = HttpRequest.BodyPublishers.concat(
                HttpRequest.BodyPublishers.ofString(
                        beginning, StandardCharsets.UTF_8),
                HttpRequest.BodyPublishers.ofFile(audioFile),
                HttpRequest.BodyPublishers.ofString(
                        ending, StandardCharsets.UTF_8)
        );

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofMinutes(2))
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
                throw new IllegalStateException(
                        "Subtitle server returned HTTP "
                                + response.statusCode());
            }

            return response.body();
        });
    }
}
