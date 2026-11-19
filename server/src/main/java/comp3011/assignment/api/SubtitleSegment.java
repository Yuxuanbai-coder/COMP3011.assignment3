package comp3011.assignment.api;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

public record SubtitleSegment(
        double start, double end, String text, @JsonInclude(JsonInclude.Include.NON_EMPTY) List<SubtitleWord> words) {
    public SubtitleSegment(double start, double end, String text) {
        this(start, end, text, List.of());
    }
}
