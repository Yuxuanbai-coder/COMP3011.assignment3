package comp3011;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;

/** Verifies the caption stage's pixel output and reuse. Developed with OpenAI Codex assistance. */
class FrameSubtitlerTest {
    @Test
    void reusesAGlyphRasterAndCompositesWhiteTextWithABlackOutline() {
        var subtitler = new FrameSubtitler(pts -> pts < 1_000_000 ? "" : "Look at that.\nCan you read it?");
        try (var frame = new Frame()) {
            frame.imageWidth = 640;
            frame.imageHeight = 360;
            subtitler.process(frame, new InfoFrame(0, 0));
            assertNull(subtitler.preparedCaption());

            subtitler.process(frame, new InfoFrame(24, 1_000_000));
            var caption = subtitler.preparedCaption();
            assertNotNull(caption);
            subtitler.process(frame, new InfoFrame(25, 1_040_000));
            assertSame(caption, subtitler.preparedCaption());
            assertTrue(caption.y() > frame.imageHeight / 2);

            int stride = frame.imageWidth * 4;
            byte[] background = new byte[stride * frame.imageHeight];
            Arrays.fill(background, (byte) 120);
            byte[] composed = new byte[caption.width() * caption.height() * 4];
            caption.composite(background, stride, composed);
            boolean white = false;
            boolean black = false;
            for (int i = 0; i < composed.length; i += 4) {
                white |= composed[i] == (byte) 255;
                black |= composed[i] == 0;
            }
            assertTrue(white && black);
            for (byte original : background) {
                assertTrue(original == (byte) 120);
            }
        }
    }
}
