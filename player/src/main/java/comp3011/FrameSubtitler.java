package comp3011;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongFunction;

import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.OpenCVFrameConverter;
import static org.bytedeco.opencv.global.opencv_imgproc.FONT_HERSHEY_SIMPLEX;
import static org.bytedeco.opencv.global.opencv_imgproc.LINE_AA;
import static org.bytedeco.opencv.global.opencv_imgproc.putText;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Point;
import org.bytedeco.opencv.opencv_core.Scalar;


public class FrameSubtitler extends FrameProcessor {

    private final AtomicBoolean subtitlesEnabled;
    private final LongFunction<String> subtitleTextAt;

    public FrameSubtitler() {
        this(new AtomicBoolean(true), _ -> "");
    }

    public FrameSubtitler(
            AtomicBoolean subtitlesEnabled,
            LongFunction<String> subtitleTextAt) {
        this.subtitlesEnabled = subtitlesEnabled;
        this.subtitleTextAt = subtitleTextAt;
    }

    String textAt(long timestampUs) {
        return timestampUs >= 0 && subtitlesEnabled.get() ? subtitleTextAt.apply(timestampUs) : "";
    }

    @Override
    public void process(Frame frame, InfoFrame info) {
        if (frame == null || frame.image == null) {
            return;
        }
        String text = textAt(info.timestampUs());
        if (text.isBlank()) {
            return;
        }

        try (OpenCVFrameConverter.ToMat converter = new OpenCVFrameConverter.ToMat();
            Point position = new Point(40, frame.imageHeight - 40);
            Scalar black = new Scalar(0, 0, 0, 255);
            Scalar white = new Scalar(255, 255, 255, 255)) {

            Mat image = converter.convert(frame);

            if (image != null) {
                String[] lines = text.split("\n");
                position.y(position.y() - 37 * (lines.length - 1));
                for (String line : lines) {
                    putText(image, line, position, FONT_HERSHEY_SIMPLEX, 1.0, black, 5, LINE_AA, false);
                    putText(image, line, position, FONT_HERSHEY_SIMPLEX, 1.0, white, 2, LINE_AA, false);
                    position.y(position.y() + 37);
                }
            }

        }
    }
}
