package comp3011;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalLong;

import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_AAC;
import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_FLAC;
import static org.bytedeco.ffmpeg.global.avutil.AV_NOPTS_VALUE;
import static org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_S16;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;

public class AudioExtractor {

    public static Path extract(
            Path videoFile, Path audioFile, long maxSeconds) throws Exception {
        if (extractChunk(videoFile, audioFile, 0, Math.multiplyExact(maxSeconds, 1_000_000L)).isEmpty()) {
            throw new IllegalStateException("No audio samples found");
        }
        return audioFile;
    }

    /** Encodes one time window and returns the first encoded sample's position in the video. */
    static OptionalLong extractChunk(Path videoFile, Path audioFile, long startUs, long durationUs)
            throws Exception {
        return extractChunk(videoFile, audioFile, startUs, durationUs, false);
    }

    static OptionalLong extractSubtitleChunk(Path videoFile, Path audioFile, long startUs, long durationUs)
            throws Exception {
        return extractChunk(videoFile, audioFile, startUs, durationUs, true);
    }

    private static OptionalLong extractChunk(
            Path videoFile, Path audioFile, long startUs, long durationUs, boolean lossless) throws Exception {
        if (startUs < 0 || durationUs <= 0) {
            throw new IllegalArgumentException("Invalid audio extraction window");
        }
        long endUs = Math.addExact(startUs, durationUs);
        if (Files.exists(audioFile)) {
            throw new IllegalArgumentException(
                    "Output file already exists: " + audioFile);
        }

        checkInterrupted();
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoFile.toFile())) {
            if (lossless) {
                // A nonmatching stream index keeps JavaCV from opening a second video decoder.
                grabber.setVideoStream(Integer.MAX_VALUE);
            }
            grabber.start();
            if (!grabber.hasAudio()) {
                return OptionalLong.empty();
            }

            long originUs = mediaStartUs(grabber);
            if (startUs > 1_000_000L) {
                // Start a little early and discard decoded packets before the requested window.
                grabber.setTimestamp(startUs - 1_000_000L);
            }
            Frame frame;
            do {
                checkInterrupted();
                frame = grabber.grabSamples();
            } while (frame != null && frame.timestamp - originUs < startUs);
            if (frame == null || frame.timestamp - originUs >= endUs) {
                return OptionalLong.empty();
            }

            long firstTimestampUs = frame.timestamp - originUs;
            try (FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(audioFile.toFile(), 1)) {
                // Whisper consumes 16 kHz mono PCM. FLAC preserves those samples and keeps uploads small.
                recorder.setSampleRate(16_000);
                if (lossless) {
                    recorder.setFormat("flac");
                    recorder.setAudioCodec(AV_CODEC_ID_FLAC);
                    recorder.setSampleFormat(AV_SAMPLE_FMT_S16);
                } else {
                    recorder.setFormat("mp4");
                    recorder.setAudioCodec(AV_CODEC_ID_AAC);
                    recorder.setAudioBitrate(32_000);
                }
                recorder.start();

                while (frame != null && frame.timestamp - originUs < endUs) {
                    checkInterrupted();
                    recorder.recordSamples(frame.sampleRate, frame.audioChannels, frame.samples);
                    frame = grabber.grabSamples();
                }
                recorder.stop();
            }
            return OptionalLong.of(firstTimestampUs);
        }
    }

    static long mediaStartUs(FFmpegFrameGrabber grabber) {
        long startUs = grabber.getFormatContext().start_time();
        return startUs == AV_NOPTS_VALUE ? 0 : startUs;
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException();
        }
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
