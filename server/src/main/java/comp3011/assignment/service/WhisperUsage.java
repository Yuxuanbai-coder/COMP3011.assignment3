package comp3011.assignment.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** A3 NOTE: Uses nullable seconds/type so missing duration usage can be rejected rather than counted as zero. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WhisperUsage(String type, Double seconds) {
}
