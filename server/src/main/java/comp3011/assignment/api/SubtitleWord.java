package comp3011.assignment.api;

/** A3 NOTE: Carries one recognized word's spelling and clip-relative timing in seconds. */
public record SubtitleWord(String word, double start, double end) {
}
