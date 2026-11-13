package comp3011.assignment.service;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WhisperResponse(List<WhisperSegment> segments, WhisperUsage usage) {
}