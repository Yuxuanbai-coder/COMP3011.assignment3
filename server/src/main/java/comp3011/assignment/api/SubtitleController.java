package comp3011.assignment.api;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import comp3011.assignment.service.SubtitleService;

/** A3 NOTE: Adds the multipart subtitle endpoint used by the JavaFX player's asynchronous client. */
@RestController
public class SubtitleController {
    private final SubtitleService subtitleService;

    /** A3 NOTE: Delegates speech recognition to the service; this class only maps the HTTP request. */
    public SubtitleController(SubtitleService subtitleService) {
        this.subtitleService = subtitleService;
    }

    /** A3 NOTE: Accepts the file part and returns timed segments as JSON for the player's subtitle timeline. */
    @PostMapping(
            value = "/api/v1/subtitles",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public SubtitleResponse subtitles(@RequestPart("file") MultipartFile file) {
        return subtitleService.transcribe(file);
    }
}
