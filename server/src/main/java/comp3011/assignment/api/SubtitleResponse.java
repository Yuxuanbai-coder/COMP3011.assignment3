package comp3011.assignment.api;
import java.util.List;

/** A3 NOTE: Defines the subtitle JSON envelope shared with the player's response parser. */
public record SubtitleResponse(List<SubtitleSegment> segments) {
    public SubtitleResponse {
        if (segments != null) {
            segments = List.copyOf(segments);
        }
    }
}
