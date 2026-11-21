package comp3011;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.OptionalLong;

/** A3 NOTE: Uses a PCM digest for exact cache hits and coarse audio features to align matching excerpts. */
record AudioFingerprint(String digest, byte[] features) {
    private static final long STEP_US = 20_000;
    private static final int STEP_SAMPLES = (int) (AudioExtractor.SUBTITLE_SAMPLE_RATE * STEP_US / 1_000_000L);
    private static final int MIN_FRAMES = 200;
    private static final int EDGE_FRAMES = 5;
    private static final int FEATURES_PER_FRAME = 2;

    /** A3 NOTE: Hashes PCM bytes and stores loudness/sample-difference features every 20 ms. */
    static AudioFingerprint read(Path pcm) throws IOException, InterruptedException {
        MessageDigest digest = sha256();
        byte[] block = new byte[STEP_SAMPLES * Short.BYTES];
        var samples = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        var features = new ByteArrayOutputStream();
        try (var source = new BufferedInputStream(Files.newInputStream(pcm))) {
            int length;
            while ((length = source.readNBytes(block, 0, block.length)) > 0) {
                checkInterrupted();
                digest.update(block, 0, length);
                int count = length / Short.BYTES;
                if (count == 0) {
                    break;
                }
                long energy = 0;
                long differenceEnergy = 0;
                int previous = samples.get(0);
                for (int i = 0; i < count; i++) {
                    int sample = samples.get(i);
                    long difference = sample - previous;
                    energy += (long) sample * sample;
                    differenceEnergy += difference * difference;
                    previous = sample;
                }
                features.write((int) Math.round(10 * Math.log10(1 + energy / (double) count)));
                features.write((int) Math.round(10 * Math.log10(1 + 256 * differenceEnergy / (1.0 + energy))));
            }
        }
        return new AudioFingerprint(HexFormat.of().formatHex(digest.digest()), smooth(features.toByteArray()));
    }

    /** A3 NOTE: Returns an excerpt's offset in microseconds only when its feature match is strong and unambiguous. */
    static OptionalLong findExcerpt(byte[] source, byte[] excerpt) throws InterruptedException {
        int excerptFrames = excerpt.length / FEATURES_PER_FRAME;
        int offsets = source.length / FEATURES_PER_FRAME - excerptFrames + 1;
        if (offsets <= 0 || excerptFrames < MIN_FRAMES) {
            return OptionalLong.empty();
        }
        int[] anchors = anchors(excerpt, 64);
        if (anchors.length < 16 || !hasDistinctPattern(excerpt, anchors)) {
            return OptionalLong.empty();
        }
        double[] scores = new double[offsets];
        int bestOffset = 0;
        for (int offset = 0; offset < offsets; offset++) {
            if (offset % 256 == 0) {
                checkInterrupted();
            }
            scores[offset] = distance(source, excerpt, offset, anchors);
            if (scores[offset] < scores[bestOffset]) {
                bestOffset = offset;
            }
        }
        // A3 NOTE: Verify the coarse search with denser anchors, then reject competing matches elsewhere.
        double best = scores[bestOffset];
        if (best > 4.0 || distance(source, excerpt, bestOffset, anchors(excerpt, 320)) > 4.0) {
            return OptionalLong.empty();
        }
        for (int offset = 0; offset < offsets; offset++) {
            if (Math.abs(offset - bestOffset) > 5 && scores[offset] < best + 0.75) {
                return OptionalLong.empty();
            }
        }
        return OptionalLong.of(bestOffset * STEP_US);
    }

    /** A3 NOTE: Smooths each feature across adjacent frames to tolerate small alignment differences. */
    private static byte[] smooth(byte[] source) {
        byte[] result = source.clone();
        for (int i = FEATURES_PER_FRAME; i < source.length - FEATURES_PER_FRAME; i++) {
            result[i] = (byte) ((source[i - FEATURES_PER_FRAME] + 2 * source[i]
                    + source[i + FEATURES_PER_FRAME] + 2) / 4);
        }
        return result;
    }

    /** A3 NOTE: Samples audible interior frames, avoiding quiet/edge regions that provide weak matching evidence. */
    private static int[] anchors(byte[] source, int limit) {
        int span = source.length / FEATURES_PER_FRAME - 2 * EDGE_FRAMES;
        int count = Math.min(span, limit);
        int[] result = new int[count];
        int used = 0;
        for (int i = 0; i < count; i++) {
            int frame = EDGE_FRAMES + (int) ((long) i * (span - 1) / (count - 1));
            if (source[frame * FEATURES_PER_FRAME] >= 28) {
                result[used++] = frame;
            }
        }
        return Arrays.copyOf(result, used);
    }

    /** A3 NOTE: Rejects almost constant loudness patterns that could match many unrelated offsets. */
    private static boolean hasDistinctPattern(byte[] source, int[] anchors) {
        int min = 100;
        int max = 0;
        for (int anchor : anchors) {
            int level = source[anchor * FEATURES_PER_FRAME];
            min = Math.min(min, level);
            max = Math.max(max, level);
        }
        return max - min >= 10;
    }

    /** A3 NOTE: Scores loudness and spectral-change differences, capping outliers to limit their influence. */
    private static double distance(byte[] source, byte[] excerpt, int offset, int[] anchors) {
        if (anchors.length == 0) {
            return Double.POSITIVE_INFINITY;
        }
        double total = 0;
        for (int anchor : anchors) {
            int sample = anchor * FEATURES_PER_FRAME;
            int sourceSample = (anchor + offset) * FEATURES_PER_FRAME;
            total += Math.min(20, Math.abs(source[sourceSample] - excerpt[sample]));
            total += 0.5 * Math.min(20, Math.abs(source[sourceSample + 1] - excerpt[sample + 1]));
        }
        return total / anchors.length;
    }

    /** A3 NOTE: Creates an independent digest instance for each audio analysis. */
    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A3 NOTE: Stops long fingerprint searches promptly when the current video is replaced. */
    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException();
        }
    }
}
