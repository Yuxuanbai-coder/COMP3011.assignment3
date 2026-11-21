/*
 * COMP3011 Assignment 2 playback-worker communication.
 *
 * Authors:
 *   1. Yuxuan Bai (a2993969), with OpenAI Codex AI assistance.
 */
package comp3011;

import java.nio.Buffer;
import java.nio.ByteBuffer;

import org.bytedeco.javacv.Frame;

/**
 * A3 NOTE: Transfers owned BGRA pixels and timing to JavaFX; arrays are recycled after copying or discarding them.
 */
record PreparedFrame(
        long playbackGeneration,
        long subtitleVersion,
        byte[] pixels,
        int width,
        int height,
        long mediaTimestampUs,
        long logicalTimestampUs,
        long targetTimeNs,
        FrameSubtitler.Caption caption,
        byte[] captionedPixels,
        SubtitleSource pendingSubtitle) {

    /** A3 NOTE: Shifts the display deadline after a pause while keeping media time and pixel ownership unchanged. */
    PreparedFrame delayedBy(long pauseDurationNs) {
        return new PreparedFrame(playbackGeneration, subtitleVersion, pixels, width, height, mediaTimestampUs,
                logicalTimestampUs, targetTimeNs + pauseDurationNs, caption, captionedPixels, pendingSubtitle);
    }

    /**
     * Retains pixels just before the first subtitle until a late cue can be rendered.
     * The frame's queue owner either returns the bytes to the pool or hands them to the worker once.
     */
    static final class SubtitleSource {
        final InfoFrame info;
        private final int stride;
        private final int channels;
        private final int depth;
        private byte[] pixels;

        SubtitleSource(Frame frame, InfoFrame info, byte[] pixels) {
            this.info = info;
            stride = frame.imageStride;
            channels = frame.imageChannels;
            depth = frame.imageDepth;
            this.pixels = pixels;
        }

        byte[] takePixels() {
            byte[] result = pixels;
            pixels = null;
            return result;
        }

        Frame wrap(int width, int height, byte[] sourcePixels) {
            Frame frame = new Frame();
            frame.imageWidth = width;
            frame.imageHeight = height;
            frame.imageStride = stride;
            frame.imageChannels = channels;
            frame.imageDepth = depth;
            frame.timestamp = info.timestampUs();
            frame.image = new Buffer[] { ByteBuffer.wrap(sourcePixels) };
            return frame;
        }
    }
}
