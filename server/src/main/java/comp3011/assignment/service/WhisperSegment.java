package comp3011.assignment.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WhisperSegment(
        double start,
        double end,
        String text,
        @JsonProperty("no_speech_prob") Double noSpeechProb,
        @JsonProperty("avg_logprob") Double avgLogProb) {
}
