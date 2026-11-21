package comp3011.assignment.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import comp3011.assignment.api.SubtitleResponse;
import comp3011.assignment.api.SubtitleSegment;
import comp3011.assignment.api.SubtitleWord;


@Service
public class SubtitleService {

    private final RestClient restClient;
    private final String apiKey;
    private final String transcriptionUrl;
    private final GlobalStatistics globalStatistics;

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

    public SubtitleResponse transcribe(MultipartFile file) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("OPENAI_API_KEY is not configured");
        }

        MultiValueMap<String, Object> requestBody = new LinkedMultiValueMap<>();
        requestBody.add("file", file.getResource());
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
        } catch (ResourceAccessException | HttpServerErrorException e) {
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

        globalStatistics.addTranscriptionSeconds(usage.seconds());

        List<WhisperSegment> whisperSegments = response.segments() == null ? List.of() : response.segments();
        List<SubtitleWord> whisperWords = response.words() == null ? List.of() : response.words();
        List<SubtitleSegment> subtitles = new ArrayList<>();

        for (WhisperSegment segment : whisperSegments) {
            if (segment.noSpeechProb() != null
                    && segment.avgLogProb() != null
                    && segment.noSpeechProb() > 0.6
                    && segment.avgLogProb() < -1.0) {
                continue;
            }

            List<SubtitleWord> words = whisperWords.stream().filter(word -> {
                double midpoint = (word.start() + word.end()) / 2;
                return midpoint >= segment.start() && midpoint < segment.end();
            }).toList();
            subtitles.add(new SubtitleSegment(segment.start(), segment.end(), segment.text(), words));
        }

        return new SubtitleResponse(subtitles);
    }
}
