package comp3011.assignment.service;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import comp3011.assignment.api.SubtitleWord;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WhisperResponse(List<WhisperSegment> segments, List<SubtitleWord> words, WhisperUsage usage) {
}
