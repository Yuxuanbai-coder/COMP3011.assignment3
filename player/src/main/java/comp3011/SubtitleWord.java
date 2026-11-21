package comp3011;

/** A3 NOTE: Stores a recognized word with clip-relative start/end seconds for phrase and boundary alignment. */
public record SubtitleWord(String word, double start, double end) {
}
