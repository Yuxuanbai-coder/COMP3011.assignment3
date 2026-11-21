package comp3011;

import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGR24;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.Arrays;
import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;

/** Verifies the caption stage's pixel output and reuse. Developed with OpenAI Codex assistance. */
class FrameSubtitlerTest {
    @Test
    void reusesAGlyphRasterAndCompositesWhiteTextWithABlackOutline() {
        var subtitler = new FrameSubtitler();
        int width = 640;
        int height = 360;
        assertNull(subtitler.prepareCaption("", width, height));
        String text = "Look at that.\nCan you read it?";
        var caption = subtitler.prepareCaption(text, width, height);
        assertNotNull(caption);
        assertSame(caption, subtitler.prepareCaption(text, width, height));
        assertTrue(caption.y() > height / 2);

        int stride = width * 4;
        byte[] background = new byte[stride * height];
        Arrays.fill(background, (byte) 120);
        byte[] composed = new byte[caption.width() * caption.height() * 4];
        caption.composite(background, stride, composed, 1);
        boolean white = false;
        boolean black = false;
        for (int i = 0; i < composed.length; i += 4) {
            white |= composed[i] == (byte) 255;
            black |= composed[i] == 0;
        }
        assertTrue(white && black);
        for (byte original : background) {
            assertEquals((byte) 120, original);
        }
    }

    @Test
    void anEarlierSubtitleProducesGlowOutsideTheTextBounds() throws Exception {
        int width = 640;
        int height = 360;
        var caption = FrameSubtitler.rasterize("Light around these letters", width, height);
        try (var finalSubtitle = new Frame(width, height, Frame.DEPTH_UBYTE, 3);
                var orderedSubtitle = new Frame(width, height, Frame.DEPTH_UBYTE, 3)) {
            applyEffects("-bS", finalSubtitle, caption);
            applyEffects("-SbS", orderedSubtitle, caption);
            ByteBuffer finalPixels = (ByteBuffer) finalSubtitle.image[0];
            ByteBuffer orderedPixels = (ByteBuffer) orderedSubtitle.image[0];
            int glowPixels = 0;
            // Every pixel above the caption is outside the drawing area of either subtitle stage.
            for (int y = 0; y < caption.y(); y++) {
                for (int x = 0; x < width; x++) {
                    int red = y * orderedSubtitle.imageStride + x * 3 + 2;
                    assertEquals(24, Byte.toUnsignedInt(finalPixels.get(red)));
                    if (Byte.toUnsignedInt(orderedPixels.get(red)) > 24) {
                        glowPixels++;
                    }
                }
            }
            assertTrue(glowPixels > 0, "Only -SbS should add glow outside the subtitle rectangle");
        }
    }

    private static void applyEffects(String options, Frame frame, FrameSubtitler.Caption caption) throws Exception {
        ByteBuffer pixels = (ByteBuffer) frame.image[0];
        for (int i = 0; i < pixels.limit(); i++) {
            pixels.put(i, (byte) 24);
        }
        var media = new InfoVideo("dark-frame", 1, frame.imageWidth, frame.imageHeight, frame.imageDepth,
                frame.imageChannels, frame.imageStride, 24, 24, 41_667, AV_PIX_FMT_BGR24);
        var info = new InfoFrame(0, 0);
        for (var option : new CommandLineController(new String[] { options }).getFrameProcessorOptions()) {
            FrameProcessor processor = option.createProcessor();
            processor.initialise(media);
            if (processor instanceof FrameSubtitler subtitler) {
                subtitler.setCaption(caption);
            }
            processor.process(frame, info);
        }
    }
}
