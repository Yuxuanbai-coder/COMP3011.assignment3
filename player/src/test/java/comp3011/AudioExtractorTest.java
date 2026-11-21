package comp3011;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.Arrays;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A3 NOTE: Uses generated PCM to verify sample-exact window extraction without a downloaded video. */
class AudioExtractorTest {
    // A3 NOTE: A known sample ramp detects shifted/truncated audio, beyond merely checking the output duration.
    @Test
    void copiesTheExactPcmSamplesAtTheRequestedPosition(@TempDir Path folder) throws Exception {
        int rate = 16_000;
        int sampleCount = 2 * rate;
        byte[] source = new byte[sampleCount * Short.BYTES];
        var samples = ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        for (int i = 0; i < sampleCount; i++) {
            samples.put((short) (i - rate));
        }
        Path input = folder.resolve("original.wav");
        Path clip = folder.resolve("clip.wav");
        try (var audio = new AudioInputStream(new ByteArrayInputStream(source),
                new AudioFormat(rate, 16, 1, true, false), sampleCount)) {
            AudioSystem.write(audio, AudioFileFormat.Type.WAVE, input.toFile());
        }
        Path decoded = folder.resolve("original.pcm");
        assertEquals(2_000_000L, AudioExtractor.decodeSubtitleAudio(input, decoded, 2_000_000L));
        var extracted = AudioExtractor.writeSubtitleChunk(decoded, clip, 400_000, 500_000).orElseThrow();
        assertEquals(400_000, extracted.startUs());
        try (var audio = AudioSystem.getAudioInputStream(clip.toFile())) {
            assertEquals(8_000, audio.getFrameLength());
            assertArrayEquals(Arrays.copyOfRange(source, 6_400 * Short.BYTES, 14_400 * Short.BYTES),
                    audio.readAllBytes());
        }
    }
}
