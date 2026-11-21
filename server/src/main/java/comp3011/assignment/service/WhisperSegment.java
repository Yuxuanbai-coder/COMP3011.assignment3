package comp3011.assignment.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** A3 NOTE: Maps segment timing and optional confidence/silence fields used to suppress likely silent-region text. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WhisperSegment(
        double start,
        double end,
        String text,
        @JsonProperty("no_speech_prob") Double noSpeechProb,
        @JsonProperty("avg_logprob") Double avgLogProb) {
}
