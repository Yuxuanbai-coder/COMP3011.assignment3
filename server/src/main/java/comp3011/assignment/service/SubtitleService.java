package comp3011.assignment.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import comp3011.assignment.api.SubtitleResponse;
import comp3011.assignment.api.SubtitleSegment;


@Service
public class SubtitleService {

    private final RestClient restClient;
    private final String apiKey;
    private final String transcriptionUrl;
    private final GlobalStatistics globalStatistics;

    public SubtitleService(
            RestClient transcriptionRestClient,
            @Value("${OPENAI_API_KEY:}") String apiKey,
            @Value("${openai.transcription-url}") String transcriptionUrl, GlobalStatistics globalStatistics) {
        this.restClient = transcriptionRestClient;
        this.apiKey = apiKey;
        this.transcriptionUrl = transcriptionUrl;
        this.globalStatistics = globalStatistics;
}
    

    public SubtitleResponse transcribe(MultipartFile file) {
        MultiValueMap<String, Object> requestBody = new LinkedMultiValueMap<>();
        if (apiKey == null || apiKey.isBlank()) {
        throw new IllegalStateException("OPENAI_API_KEY is not configured");
    }
        

        requestBody.add("file", file.getResource());
        requestBody.add("model", "whisper-1");
        requestBody.add("response_format", "verbose_json");
        requestBody.add("timestamp_granularities[]", "word");
        requestBody.add("timestamp_granularities[]", "segment");
        System.out.println("model: " + requestBody.getFirst("model"));
        System.out.println("response format: " + requestBody.getFirst("response_format"));
        System.out.println("timestamps: " + requestBody.get("timestamp_granularities[]"));
        WhisperResponse response = restClient.post()
        .uri(transcriptionUrl)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
        .contentType(MediaType.MULTIPART_FORM_DATA)
        .body(requestBody)
        .retrieve()
        .body(WhisperResponse.class);

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

        List<SubtitleSegment> subtitles = new ArrayList<>();

        for (WhisperSegment segment : whisperSegments) {
            if (segment.noSpeechProb() != null
                    && segment.avgLogProb() != null
                    && segment.noSpeechProb() > 0.6
                    && segment.avgLogProb() < -1.0) {
                continue;
            }

            subtitles.add(new SubtitleSegment(
                    segment.start(),
                    segment.end(),
                    segment.text()));
        }

        return new SubtitleResponse(subtitles);
    }
}
