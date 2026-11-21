/*
 * COMP3011 Assignment 2 playback-worker communication.
 *
 * Authors:
 *   1. Yuxuan Bai (a2993969), with OpenAI Codex AI assistance.
 */
package comp3011;

/**
 * A3 NOTE: Transfers owned BGRA pixels and timing to JavaFX; arrays are recycled after copying or discarding them.
 */
record PreparedFrame(
        long playbackGeneration,
        byte[] pixels,
        int width,
        int height,
        long mediaTimestampUs,
        long logicalTimestampUs,
        long targetTimeNs) {

    /** A3 NOTE: Shifts the display deadline after a pause while keeping media time and pixel ownership unchanged. */
    PreparedFrame delayedBy(long pauseDurationNs) {
        return new PreparedFrame(playbackGeneration, pixels, width, height, mediaTimestampUs,
                logicalTimestampUs, targetTimeNs + pauseDurationNs);
    }
}
