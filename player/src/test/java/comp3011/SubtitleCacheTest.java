package comp3011;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SubtitleCacheTest {
    @Test
    void reopensACompletedTrackByAudioContentAndRebasesAnExcerpt(@TempDir Path directory) throws Exception {
        var source = patternedAudio(20, 2430);
        Path original = directory.resolve("original.pcm");
        Files.write(original, source.array());
        Path renamed = directory.resolve("other-name.pcm");
        Files.copy(original, renamed);

        Path cacheDirectory = directory.resolve("subtitle-cache");
        var cue = new SubtitleSegment(8.25, 9.5, "Excerpt dialogue");
        new SubtitleCache(cacheDirectory).save(AudioFingerprint.read(original), List.of(cue));
        var cache = new SubtitleCache(cacheDirectory);
        assertEquals(List.of(cue), cache.find(AudioFingerprint.read(renamed), 20_000_000).orElseThrow());

        int rate = AudioExtractor.SUBTITLE_SAMPLE_RATE;
        int startMs = 7_371;
        int first = startMs * rate / 1000;
        var clip = ByteBuffer.allocate(8 * rate * Short.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 8 * rate; i++) {
            clip.putShort((short) (source.getShort((i + first) * Short.BYTES) * 0.97));
        }
        Path excerpt = directory.resolve("excerpt.pcm");
        Files.write(excerpt, clip.array());

        var shifted = cache.find(AudioFingerprint.read(excerpt), 8_000_000).orElseThrow().getFirst();
        assertEquals("Excerpt dialogue", shifted.text());
        assertEquals(cue.start() - startMs / 1000.0, shifted.start(), 0.04);
        assertEquals(cue.end() - startMs / 1000.0, shifted.end(), 0.04);

        Path unrelated = directory.resolve("unrelated.pcm");
        Files.write(unrelated, patternedAudio(8, 221).array());
        assertTrue(cache.find(AudioFingerprint.read(unrelated), 8_000_000).isEmpty());
    }

    private static ByteBuffer patternedAudio(int seconds, int seed) {
        int rate = AudioExtractor.SUBTITLE_SAMPLE_RATE;
        var audio = ByteBuffer.allocate(seconds * rate * Short.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        var random = new Random(seed);
        int blockLength = rate * 80 / 1000;
        for (int start = 0; start < seconds * rate; start += blockLength) {
            double amplitude = 250 * Math.pow(2, random.nextDouble() * 5);
            double frequency = 180 + random.nextInt(1_700);
            for (int i = 0; i < blockLength; i++) {
                audio.putShort((short) (amplitude * Math.sin(2 * Math.PI * frequency * i / rate)));
            }
        }
        return audio;
    }
}
