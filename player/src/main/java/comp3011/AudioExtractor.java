package comp3011;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_AAC;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;

public class AudioExtractor {

    public static Path extract(
            Path videoFile, Path audioFile, long maxSeconds) throws Exception {

        if (maxSeconds <= 0) {
            throw new IllegalArgumentException("Duration must be positive");
        }

        if (Files.exists(audioFile)) {
            throw new IllegalArgumentException(
                    "Output file already exists: " + audioFile);
        }

        try (FFmpegFrameGrabber grabber =
                     new FFmpegFrameGrabber(videoFile.toFile())) {

            grabber.start();

            if (grabber.getAudioChannels() <= 0) {
                throw new IllegalStateException("The video has no audio track");
            }

            try (FFmpegFrameRecorder recorder =
                         new FFmpegFrameRecorder(audioFile.toFile(), 1)) {

                recorder.setFormat("mp4");
                recorder.setAudioCodec(AV_CODEC_ID_AAC);
                recorder.setAudioChannels(1);
                recorder.setSampleRate(16_000);
                recorder.setAudioBitrate(32_000);
                recorder.start();

                Frame frame;
                long firstTimestampUs = Long.MIN_VALUE;

                while ((frame = grabber.grabSamples()) != null) {
                    if (firstTimestampUs == Long.MIN_VALUE) {
                        firstTimestampUs = frame.timestamp;
                    }

                    long elapsedUs = frame.timestamp - firstTimestampUs;
                    if (elapsedUs >= maxSeconds * 1_000_000L) {
                        break;
                    }

                    recorder.recordSamples(
                            frame.sampleRate,
                            frame.audioChannels,
                            frame.samples
                    );
                }

                recorder.stop();

                if (firstTimestampUs == Long.MIN_VALUE) {
                    throw new IllegalStateException("No audio samples found");
                }
            }

            grabber.stop();
        }

        return audioFile;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.out.println("Usage: AudioExtractor <video-file> <audio-file>");
            return;
        }

        Path videoFile = Path.of(args[0]);
        Path audioFile = Path.of(args[1]);

        Path result = extract(videoFile, audioFile, 20);

        System.out.println("Audio saved to: " + result.toAbsolutePath());
        System.out.println("Size: " + Files.size(result) + " bytes");
    }
}