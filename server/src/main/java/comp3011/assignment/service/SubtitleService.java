package comp3011.assignment.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import comp3011.assignment.api.SubtitleResponse;
import comp3011.assignment.api.SubtitleSegment;
import comp3011.assignment.api.SubtitleWord;


/** Converts uploaded English audio into timed subtitles and records usage. Developed with OpenAI Codex assistance. */
@Service
public class SubtitleService {

    private final RestClient restClient;
    private final String apiKey;
    private final String transcriptionUrl;
    private final GlobalStatistics globalStatistics;

    /** A3 NOTE: Injects the shared HTTP client, server-side key, upstream URL and process-wide usage counters. */
    public SubtitleService(
            RestClient transcriptionRestClient,
            @Value("${OPENAI_API_KEY:}") String apiKey,
            @Value("${openai.transcription-url}") String transcriptionUrl,
            GlobalStatistics globalStatistics) {
        this.restClient = transcriptionRestClient;
        this.apiKey = apiKey;
        this.transcriptionUrl = transcriptionUrl;
        this.globalStatistics = globalStatistics;
    }

    /** A3 NOTE: Requests verbose word/segment timestamps, filters likely silence and groups retained words by segment. */
    public SubtitleResponse transcribe(MultipartFile file) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("OPENAI_API_KEY is not configured");
        }

        MultiValueMap<String, Object> requestBody = new LinkedMultiValueMap<>();
        requestBody.add("file", file.getResource());
        // A3 NOTE: Subtitle requests need timestamped verbose output, independently of the older plain-text endpoint.
        requestBody.add("model", "whisper-1");
        requestBody.add("response_format", "verbose_json");
        requestBody.add("timestamp_granularities[]", "word");
        requestBody.add("timestamp_granularities[]", "segment");
        requestBody.add("language", "en");
        WhisperResponse response;
        try {
            response = restClient.post()
                    .uri(transcriptionUrl)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(requestBody)
                    .retrieve()
                    .body(WhisperResponse.class);
        } catch (ResourceAccessException | HttpServerErrorException | HttpClientErrorException.TooManyRequests e) {
            throw new TranscriptionServiceException("Subtitle transcription service is unavailable", e);
        }

        if (response == null) {
            throw new IllegalStateException("Transcription response is empty");
        }

        WhisperUsage usage = response.usage();

        if (usage == null || usage.seconds() == null
                || !"duration".equals(usage.type())) {
            throw new IllegalStateException("Transcription usage is unavailable");
        }

        // A3 NOTE: Count the duration reported by each successful upstream response, including overlapping request audio.
        globalStatistics.addTranscriptionSeconds(usage.seconds());

        List<WhisperSegment> whisperSegments = response.segments() == null ? List.of() : response.segments();
        List<SubtitleWord> whisperWords = response.words() == null ? List.of() : response.words();
        List<SubtitleSegment> subtitles = new ArrayList<>();

        for (WhisperSegment segment : whisperSegments) {
            // A3 NOTE: Discard a segment only when both silence probability and low recognition confidence support it.
            if (segment.noSpeechProb() != null
                    && segment.avgLogProb() != null
                    && segment.noSpeechProb() > 0.6
                    && segment.avgLogProb() < -1.0) {
                continue;
            }

            List<SubtitleWord> words = whisperWords.stream().filter(word -> {
                // A3 NOTE: A midpoint assigns a boundary word to one segment without duplicating it.
                double midpoint = (word.start() + word.end()) / 2;
                return midpoint >= segment.start() && midpoint < segment.end();
            }).toList();
            subtitles.add(new SubtitleSegment(segment.start(), segment.end(), segment.text(), words));
        }

        return new SubtitleResponse(subtitles);
    }
}
