package comp3011.assignment.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WhisperUsage(String type, Double seconds) {
}