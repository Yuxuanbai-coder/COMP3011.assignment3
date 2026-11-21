package comp3011.assignment.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import comp3011.assignment.service.SubtitleService;

/** Verifies invalid subtitle uploads with a stub service. Developed with OpenAI Codex assistance. */
class SubtitleControllerTest {
    @Test
    void returnsBadRequestForMissingAndEmptyAudio() throws Exception {
        var service = mock(SubtitleService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new SubtitleController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
        var empty = new MockMultipartFile("file", "empty.wav", "audio/wav", new byte[0]);

        mvc.perform(multipart("/api/v1/subtitles"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
        mvc.perform(multipart("/api/v1/subtitles").file(empty))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
        verifyNoInteractions(service);
    }
}
