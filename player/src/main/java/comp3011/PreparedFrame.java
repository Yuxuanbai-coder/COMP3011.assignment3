/*
 * COMP3011 Assignment 2 playback-worker communication.
 *
 * Authors:
 *   1. Yuxuan Bai (a2993969), with OpenAI Codex AI assistance.
 */
package comp3011;

/**
 * Prepared pixels and their timing metadata, transferred to the JavaFX thread.
 */
record PreparedFrame(
        long playbackGeneration,
        byte[] pixels,
        int width,
        int height,
        long mediaTimestampUs,
        long logicalTimestampUs,
        long targetTimeNs) {

    PreparedFrame delayedBy(long pauseDurationNs) {
        return new PreparedFrame(playbackGeneration, pixels, width, height, mediaTimestampUs,
                logicalTimestampUs, targetTimeNs + pauseDurationNs);
    }
}
