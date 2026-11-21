package comp3011;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.function.LongFunction;

import org.bytedeco.javacv.Frame;

/** Rasterizes each frame's cue for composition into the video. Developed with OpenAI Codex assistance. */
final class FrameSubtitler extends FrameProcessor {
    private static final FontRenderContext FONT_CONTEXT = new FontRenderContext(null, true, true);
    private static final Font CAPTION_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 1);

    private final LongFunction<String> textAt;
    private Caption lastRaster;
    private Caption preparedCaption;

    FrameSubtitler(LongFunction<String> textAt) {
        this.textAt = textAt;
    }

    @Override
    public void process(Frame frame, InfoFrame info) {
        String text = textAt.apply(info.timestampUs());
        if (text.isEmpty()) {
            preparedCaption = null;
            return;
        }
        if (lastRaster == null || !lastRaster.matches(text, frame.imageWidth, frame.imageHeight)) {
            lastRaster = rasterize(text, frame.imageWidth, frame.imageHeight);
        }
        preparedCaption = lastRaster;
    }

    Caption preparedCaption() {
        return preparedCaption;
    }

    static Caption rasterize(String text, int frameWidth, int frameHeight) {
        String[] lines = text.split("\n");
        float size = Math.clamp(frameHeight * 0.052f, 7f, 160f);
        Font font = CAPTION_FONT.deriveFont(size);
        double widest = 0;
        for (String line : lines) {
            widest = Math.max(widest, font.getStringBounds(line, FONT_CONTEXT).getWidth());
        }
        double scale = Math.min(1, frameWidth * 0.9 / (widest + size * 0.18));
        font = font.deriveFont((float) (size * scale));
        var metrics = font.getLineMetrics("Ag", FONT_CONTEXT);
        float baseline = metrics.getAscent();
        var glyphs = new Path2D.Double();
        for (String line : lines) {
            if (!line.isBlank()) {
                var layout = new TextLayout(line, font, FONT_CONTEXT);
                double left = (frameWidth - layout.getAdvance()) / 2;
                glyphs.append(layout.getOutline(AffineTransform.getTranslateInstance(left, baseline)), false);
            }
            baseline += metrics.getHeight();
        }
        var bounds = glyphs.getBounds2D();
        if (bounds.isEmpty()) {
            return null;
        }
        float stroke = Math.max(1f, font.getSize2D() / 6);
        int padding = (int) Math.ceil(stroke / 2) + 1;
        int left = Math.max(0, (int) Math.floor(bounds.getMinX()) - padding);
        int right = Math.min(frameWidth, (int) Math.ceil(bounds.getMaxX()) + padding);
        int top = (int) Math.floor(bounds.getMinY()) - padding;
        int width = right - left;
        int height = Math.min(frameHeight, (int) Math.ceil(bounds.getMaxY()) + padding - top);
        int y = Math.max(0, (int) (frameHeight * 0.93) - height);
        var raster = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE);
        var graphics = raster.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.translate(-left, -top);
            graphics.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            graphics.setColor(Color.BLACK);
            graphics.draw(glyphs);
            graphics.setColor(Color.WHITE);
            graphics.fill(glyphs);
        } finally {
            graphics.dispose();
        }
        int[] pixels = ((DataBufferInt) raster.getRaster().getDataBuffer()).getData();
        return new Caption(text, frameWidth, frameHeight, left, y, width, height, pixels);
    }

    /** Composites a small, premultiplied caption rectangle over opaque BGRA video pixels. */
    record Caption(String text, int frameWidth, int frameHeight, int x, int y, int width, int height, int[] pixels) {
        boolean matches(String candidate, int width, int height) {
            return frameWidth == width && frameHeight == height && text.equals(candidate);
        }

        void composite(byte[] frame, int frameStride, byte[] output) {
            int maskIndex = 0;
            int destination = 0;
            for (int row = 0; row < height; row++) {
                int source = (y + row) * frameStride + x * 4;
                for (int column = 0; column < width; column++) {
                    int pixel = pixels[maskIndex++];
                    int uncovered = 255 - (pixel >>> 24);
                    output[destination++] = over(frame[source++], pixel & 255, uncovered);
                    output[destination++] = over(frame[source++], (pixel >>> 8) & 255, uncovered);
                    output[destination++] = over(frame[source++], (pixel >>> 16) & 255, uncovered);
                    output[destination++] = (byte) 255;
                    source++;
                }
            }
        }

        private static byte over(byte background, int foreground, int uncovered) {
            return (byte) (foreground + ((background & 255) * uncovered + 127) / 255);
        }
    }
}
