package comp3011;

import java.util.List;

/** A3 NOTE: Cue bounds use seconds; raw cues are clip-relative and formatted/cache cues are video-relative. */
public record SubtitleSegment(double start, double end, String text, List<SubtitleWord> words) {
    /** A3 NOTE: Display-ready or cached cues need no per-word timing list. */
    public SubtitleSegment(double start, double end, String text) {
        this(start, end, text, List.of());
    }
}
