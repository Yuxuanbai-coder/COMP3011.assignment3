package comp3011;

import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.OpenCVFrameConverter;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Point;
import org.bytedeco.opencv.opencv_core.Scalar;

import static org.bytedeco.opencv.global.opencv_imgproc.FONT_HERSHEY_SIMPLEX;
import static org.bytedeco.opencv.global.opencv_imgproc.LINE_AA;
import static org.bytedeco.opencv.global.opencv_imgproc.putText;

public class FrameSubtitler extends FrameProcessor{
    @Override 
    public void process(Frame frame, InfoFrame info) throws Exception{
        if(frame == null || frame.image == null){
            return ;
        }
        long timeUs = info.timestampUs();
        if(timeUs < 2000000L || timeUs >= 5000000L){
            return;
        }

        try (OpenCVFrameConverter.ToMat converter = new OpenCVFrameConverter.ToMat();
            Point position = new Point(40, frame.imageHeight - 40);
            Scalar black = new Scalar(0, 0, 0, 255);
            Scalar white = new Scalar(255, 255, 255, 255)) {

            Mat image = converter.convert(frame);

            if (image != null) {
                putText(image, "Hello A3", position,
                        FONT_HERSHEY_SIMPLEX, 1.0, black, 5, LINE_AA, false);

                putText(image, "Hello A3", position,
                        FONT_HERSHEY_SIMPLEX, 1.0, white, 2, LINE_AA, false);
            }

        }
   }
}