package comp3011;

import java.nio.ShortBuffer;
import java.util.BitSet;

/** A3 NOTE: Measures audible PCM slices to skip silent uploads and reject cues over silence; this is not speech recognition. */
final class AudioActivity {
    private static final long SLICE_US = 25_000;
    private static final long TIMING_MARGIN_US = 50_000;
    private static final long LONG_SILENT_LEAD_US = 500_000;
    private static final long MIN_AUDIO_FOR_LATE_CUE_US = 200_000;
    private static final long MIN_QUIET_LEAD_US = 1_000_000;
    private static final long RETRY_PREROLL_US = 400_000;
    private static final int SUSTAINED_SLICES = 4;
    private static final int SILENCE_PEAK = 16;

    private final BitSet audibleSlices = new BitSet();

    /** A3 NOTE: Marks each 25 ms slice containing a sample above the near-silence threshold. */
    static AudioActivity fromPcm(ShortBuffer samples, int sampleRate) {
        var activity = new AudioActivity();
        int samplesPerSlice = (int) (sampleRate * SLICE_US / 1_000_000L);
        for (int begin = 0, slice = 0; begin < samples.limit(); begin += samplesPerSlice, slice++) {
            int end = Math.min(samples.limit(), begin + samplesPerSlice);
            for (int i = begin; i < end; i++) {
                if (Math.abs(samples.get(i)) > SILENCE_PEAK) {
                    activity.audibleSlices.set(slice);
                    break;
                }
            }
        }
        return activity;
    }

    /** A3 NOTE: A completely silent window can finish without an online transcription request. */
    boolean containsSignal() {
        return !audibleSlices.isEmpty();
    }

    /** A3 NOTE: Finds sustained sound after a quiet opening, retaining preroll for an empty-result retry. */
    long retryAfterQuietLeadUs() {
        int first = audibleSlices.nextSetBit(0);
        while (first >= 0) {
            int end = audibleSlices.nextClearBit(first);
            if (end - first >= SUSTAINED_SLICES) {
                long startUs = first * SLICE_US;
                return startUs >= MIN_QUIET_LEAD_US ? startUs - RETRY_PREROLL_US : 0;
            }
            first = audibleSlices.nextSetBit(end);
        }
        return 0;
    }

    /** A3 NOTE: Checks for enough audible samples near a proposed cue, allowing small timing errors. */
    boolean supportsCue(double startSeconds, double endSeconds) {
        long startUs = Math.round(startSeconds * 1_000_000);
        long endUs = Math.round(endSeconds * 1_000_000);
        int first = (int) (Math.max(0, startUs - TIMING_MARGIN_US) / SLICE_US);
        int next = audibleSlices.nextSetBit(first);
        if (next < 0 || next * SLICE_US >= endUs + TIMING_MARGIN_US) {
            return false;
        }
        if (next * SLICE_US - startUs <= LONG_SILENT_LEAD_US) {
            return true;
        }

        // A nearby next line can touch the end of a cue that otherwise lies in silence.
        long minAudioUs = Math.min(MIN_AUDIO_FOR_LATE_CUE_US, (endUs - startUs) / 5);
        long audibleUs = 0;
        while (next >= 0 && next * SLICE_US < endUs) {
            audibleUs += Math.min(SLICE_US, endUs - next * SLICE_US);
            if (audibleUs >= minAudioUs) {
                return true;
            }
            next = audibleSlices.nextSetBit(next + 1);
        }
        return false;
    }
}
