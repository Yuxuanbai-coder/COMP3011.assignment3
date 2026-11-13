package comp3011;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.OpenCVFrameConverter;
import static org.bytedeco.opencv.global.opencv_imgproc.FONT_HERSHEY_SIMPLEX;
import static org.bytedeco.opencv.global.opencv_imgproc.LINE_AA;
import static org.bytedeco.opencv.global.opencv_imgproc.putText;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Point;
import org.bytedeco.opencv.opencv_core.Scalar;


public class FrameSubtitler extends FrameProcessor{

    private final AtomicBoolean subtitlesEnabled;
    private final AtomicReference<List<SubtitleSegment>> subtitleSegments;

    public FrameSubtitler() {
        this(new AtomicBoolean(true), new AtomicReference<>(List.of()));
    }

    public FrameSubtitler(
            AtomicBoolean subtitlesEnabled,
            AtomicReference<List<SubtitleSegment>> subtitleSegments) {
        this.subtitlesEnabled = subtitlesEnabled;
        this.subtitleSegments = subtitleSegments;
    }

    @Override 
    public void process(Frame frame, InfoFrame info) throws Exception{
        if(frame == null || frame.image == null || !subtitlesEnabled.get()){
            return ;
        }
        double timeSeconds = info.timestampUs() / 1_000_000.0;
        String text = null;

        for (SubtitleSegment segment : subtitleSegments.get()) {
            if (timeSeconds >= segment.start() && timeSeconds < segment.end()) {
                text = segment.text();
                break;
            }
        }

        if (text == null || text.isBlank()) {
            return;
        }

        try (OpenCVFrameConverter.ToMat converter = new OpenCVFrameConverter.ToMat();
            Point position = new Point(40, frame.imageHeight - 40);
            Scalar black = new Scalar(0, 0, 0, 255);
            Scalar white = new Scalar(255, 255, 255, 255)) {

            Mat image = converter.convert(frame);

            if (image != null) {
                putText(image, text, position,
                FONT_HERSHEY_SIMPLEX, 1.0, black, 5, LINE_AA, false);

                putText(image, text, position,
                FONT_HERSHEY_SIMPLEX, 1.0, white, 5, LINE_AA, false);
            }

        }
   }
}