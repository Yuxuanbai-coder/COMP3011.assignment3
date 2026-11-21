package comp3011;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ShortBuffer;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Covers subtitle timing and line limits. Developed with OpenAI Codex assistance. */
class SubtitleFormatterTest {
    @Test
    void keepsTimedWordsAtTheWindowBoundaryWhenTheFinalWordHasNoTimestamp() {
        var segment = new SubtitleSegment(0, 4.5, "Before the turn, follow the yellow road.", List.of(
                new SubtitleWord("Before", 0, 0.3), new SubtitleWord("the", 0.3, 0.5),
                new SubtitleWord("turn", 0.5, 0.9), new SubtitleWord("follow", 2.25, 2.55),
                new SubtitleWord("the", 2.55, 2.8), new SubtitleWord("yellow", 2.8, 3.4)));

        assertEquals(List.of(new SubtitleSegment(20.25, 22.5, "follow the yellow road.")),
                SubtitleFormatter.format(List.of(segment), 18_000_000L, 20_000_000L, 40_000_000L));
    }

    @Test
    void omitsRecognizedTextOnlyInSilentRanges() {
        int sampleRate = 16_000;
        short[] samples = new short[3 * sampleRate];
        Arrays.fill(samples, 187 * sampleRate / 100, samples.length, (short) 1000);
        var activity = AudioActivity.fromPcm(ShortBuffer.wrap(samples), sampleRate);
        var segments = List.of(new SubtitleSegment(0.3, 2.02, "Invented line"),
                new SubtitleSegment(2.0, 2.8, "Audible speech"));

        assertEquals(List.of(new SubtitleSegment(62, 63, "Audible speech")),
                SubtitleFormatter.format(segments, 60_000_000L, 60_000_000L, 120_000_000L, activity));
    }

    @Test
    void holdsShortDialogueAtItsPositionInTheVideo() {
        var cues = SubtitleFormatter.format(List.of(new SubtitleSegment(0.5, 0.6, "Hello")),
                40_000_000L, 40_000_000L, 60_000_000L);

        assertEquals(List.of(new SubtitleSegment(40.5, 41.5, "Hello")), cues);
    }

    @Test
    void splitsLongTextIntoReadableCuesWithoutLosingCharacters() {
        String text = "\u5b57\u5e55\ud83c\udfa5".repeat(50);
        var cues = SubtitleFormatter.format(List.of(new SubtitleSegment(0, 12, text)), 0, 0, 20_000_000L);

        StringBuilder reconstructed = new StringBuilder();
        assertTrue(cues.size() > 1);
        for (SubtitleSegment cue : cues) {
            String[] lines = cue.text().split("\n");
            assertTrue(lines.length <= 2);
            assertTrue(cue.end() - cue.start() <= 7);
            for (String line : lines) {
                assertTrue(line.codePointCount(0, line.length()) <= 42);
                reconstructed.append(line);
            }
        }
        assertEquals(text, reconstructed.toString());
    }

    @Test
    void usesRealWordTimesOnEitherSideOfAPause() {
        var speech = new SubtitleSegment(0.5, 7.2, "Hello, world! I’m back.", List.of(
                new SubtitleWord("hello", 0.5, 0.7), new SubtitleWord("world", 0.7, 1.0),
                new SubtitleWord("I’m", 6.0, 6.3), new SubtitleWord("back", 6.7, 7.2)));

        var cues = SubtitleFormatter.format(List.of(speech), 39_000_000L, 40_000_000L, 60_000_000L);
        assertEquals(List.of(new SubtitleSegment(45.0, 46.2, "I’m back.")), cues);

        var earlier = SubtitleFormatter.format(List.of(speech), 40_000_000L, 40_000_000L, 60_000_000L);
        assertEquals(List.of(new SubtitleSegment(40.5, 41.5, "Hello, world!"),
                new SubtitleSegment(46, 47.2, "I’m back.")), earlier);
    }

    @Test
    void givesABoundaryWordToOnlyOneWindow() {
        var speech = new SubtitleSegment(0.1, 1.4, "Before, boundary.", List.of(
                new SubtitleWord("Before", 0.1, 0.4), new SubtitleWord("boundary", 0.95, 1.4)));

        var before = SubtitleFormatter.format(List.of(speech), 19_000_000L, 0, 20_000_000L);
        var after = SubtitleFormatter.format(List.of(speech), 19_000_000L, 20_000_000L, 40_000_000L);

        assertEquals(List.of(new SubtitleSegment(19.1, 20, "Before,")), before);
        assertEquals(List.of(new SubtitleSegment(20, 21, "boundary.")), after);
    }
}
