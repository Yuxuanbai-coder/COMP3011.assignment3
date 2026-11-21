package comp3011;

import java.util.List;

/** A3 NOTE: Matches the server's JSON envelope containing the recognized subtitle segments. */
public record SubtitleResponse(List<SubtitleSegment> segments) {
}
