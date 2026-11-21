package comp3011.assignment.api;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/** A3 NOTE: Returns segment text and clip-relative seconds, omitting the optional word list when it is empty. */
public record SubtitleSegment(
        double start, double end, String text, @JsonInclude(JsonInclude.Include.NON_EMPTY) List<SubtitleWord> words) {
    /** A3 NOTE: Allows callers to construct a segment when only segment-level timing is available. */
    public SubtitleSegment(double start, double end, String text) {
        this(start, end, text, List.of());
    }
}
