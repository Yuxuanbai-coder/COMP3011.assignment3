package comp3011.assignment.service;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import comp3011.assignment.api.SubtitleSegment;
import comp3011.assignment.api.SubtitleWord;

class SubtitleServiceTest {
    @Test
    void sendsLosslessAudioAndReturnsWordTimesOnlyForRetainedSegments() {
        String url = "https://stub.example.test/v1/audio/transcriptions";
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer upstream = MockRestServiceServer.bindTo(builder).build();
        GlobalStatistics usage = new GlobalStatistics();
        SubtitleService service = new SubtitleService(builder.build(), "test-key", url, usage);

        upstream.expect(requestTo(url)).andExpect(method(HttpMethod.POST))
                .andExpect(content().string(allOf(containsString("filename=\"clip.flac\""),
                        containsString("whisper-1"), containsString("verbose_json"),
                        containsString("name=\"timestamp_granularities[]\""),
                        containsString("\r\n\r\nword\r\n"), containsString("\r\n\r\nsegment\r\n"),
                        containsString("name=\"language\""), containsString("\r\n\r\nen\r\n"))))
                .andRespond(withSuccess("""
                        {
                          "segments": [
                            {"start":0.5,"end":1.4,"text":"Hello, world!"},
                            {"start":1.5,"end":2.5,"text":"silence","no_speech_prob":0.9,"avg_logprob":-1.2},
                            {"start":2.5,"end":3.5,"text":"Bye.","no_speech_prob":0.9,"avg_logprob":-0.5}
                          ],
                          "words": [
                            {"word":"Hello","start":0.5,"end":0.9},
                            {"word":"world","start":1.0,"end":1.4},
                            {"word":"silence","start":1.6,"end":2.4},
                            {"word":"Bye","start":2.6,"end":3.3}
                          ],
                          "usage":{"type":"duration","seconds":4}
                        }
                        """, MediaType.APPLICATION_JSON));

        var clip = new MockMultipartFile("file", "clip.flac", "audio/flac", new byte[] {1, 2, 3});
        var response = service.transcribe(clip);

        assertEquals(List.of(new SubtitleSegment(0.5, 1.4, "Hello, world!", List.of(
                new SubtitleWord("Hello", 0.5, 0.9), new SubtitleWord("world", 1, 1.4))),
                new SubtitleSegment(2.5, 3.5, "Bye.", List.of(new SubtitleWord("Bye", 2.6, 3.3)))),
                response.segments());
        assertEquals(4, usage.getTranscriptionSeconds());
        upstream.verify();
    }
}
