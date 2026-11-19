package comp3011;

import java.util.List;

public record SubtitleSegment(double start, double end, String text, List<SubtitleWord> words) {
    public SubtitleSegment(double start, double end, String text) {
        this(start, end, text, List.of());
    }
}
