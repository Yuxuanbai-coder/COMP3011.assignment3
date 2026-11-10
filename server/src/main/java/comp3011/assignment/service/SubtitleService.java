package comp3011.assignment.service;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;
import comp3011.assignment.api.SubtitleSegment;
import comp3011.assignment.api.SubtitleResponse;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;


@Service
public class SubtitleService {
    

    public SubtitleResponse transcribe(MultipartFile file) {
        MultiValueMap<String, Object> requestBody = new LinkedMultiValueMap<>();

        requestBody.add("file", file.getResource());
        requestBody.add("model", "whisper-1");
        requestBody.add("response_format", "verbose_json");
        requestBody.add("timestamp_granularities[]", "word");
        requestBody.add("timestamp_granularities[]", "segment");
        System.out.println("model: " + requestBody.getFirst("model"));
        System.out.println("response format: " + requestBody.getFirst("response_format"));
        System.out.println("timestamps: " + requestBody.get("timestamp_granularities[]"));
        SubtitleSegment segment = new SubtitleSegment(0.5, 2.5, "Hello A3");
        return new SubtitleResponse(List.of(segment));
    }
}
