package comp3011;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalLong;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_AAC;
import static org.bytedeco.ffmpeg.global.avutil.AV_NOPTS_VALUE;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;

public class AudioExtractor {
    private static final int SUBTITLE_SAMPLE_RATE = 16_000;
    private static final int TIMESTAMP_ROUNDING_SAMPLES = SUBTITLE_SAMPLE_RATE / 500;

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
        long endUs = validateWindow(audioFile, startUs, durationUs);
        checkInterrupted();
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoFile.toFile())) {
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
                recorder.setSampleRate(SUBTITLE_SAMPLE_RATE);
                recorder.setFormat("mp4");
                recorder.setAudioCodec(AV_CODEC_ID_AAC);
                recorder.setAudioBitrate(32_000);
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

    /** Copies resampled PCM to its original position on the clip's timeline and writes a WAV. */
    static Optional<SubtitleAudio> extractSubtitleChunk(Path videoFile, Path audioFile, long startUs, long durationUs)
            throws Exception {
        long endUs = validateWindow(audioFile, startUs, durationUs);
        int sampleCount = Math.toIntExact(samplesAt(durationUs));
        byte[] bytes = new byte[Math.multiplyExact(sampleCount, Short.BYTES)];
        ShortBuffer pcm = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        int filledUntil = 0;
        checkInterrupted();
        try (var grabber = new FFmpegFrameGrabber(videoFile.toFile())) {
            grabber.setVideoStream(Integer.MAX_VALUE);
            grabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
            grabber.setAudioChannels(1);
            grabber.setSampleRate(SUBTITLE_SAMPLE_RATE);
            grabber.start();
            long originUs = mediaStartUs(grabber);
            if (startUs > 1_000_000L) {
                grabber.setTimestamp(startUs - 1_000_000L);
            }
            Frame frame;
            while ((frame = grabber.grabSamples()) != null && frame.timestamp - originUs < endUs) {
                checkInterrupted();
                ShortBuffer samples = (ShortBuffer) frame.samples[0];
                long offset = samplesAt(frame.timestamp - originUs - startUs);
                if (filledUntil > 0 && Math.abs(offset - filledUntil) <= TIMESTAMP_ROUNDING_SAMPLES) {
                    // Container timestamps can round consecutive PCM frames to slightly different boundaries.
                    offset = filledUntil;
                }
                long destination = Math.max(filledUntil, offset);
                long skip = destination - offset;
                long count = Math.min(sampleCount - destination, samples.remaining() - skip);
                if (count > 0) {
                    pcm.put((int) destination, samples, samples.position() + (int) skip, (int) count);
                    filledUntil = (int) (destination + count);
                }
            }
        }
        if (filledUntil == 0) {
            return Optional.empty();
        }
        AudioFormat format = new AudioFormat(SUBTITLE_SAMPLE_RATE, 16, 1, true, false);
        try (var audio = new AudioInputStream(new ByteArrayInputStream(bytes), format, sampleCount)) {
            AudioSystem.write(audio, AudioFileFormat.Type.WAVE, audioFile.toFile());
        }
        return Optional.of(new SubtitleAudio(startUs, AudioActivity.fromPcm(pcm, SUBTITLE_SAMPLE_RATE)));
    }

    private static long validateWindow(Path audioFile, long startUs, long durationUs) {
        if (startUs < 0 || durationUs <= 0) {
            throw new IllegalArgumentException("Invalid audio extraction window");
        }
        if (Files.exists(audioFile)) {
            throw new IllegalArgumentException("Output file already exists: " + audioFile);
        }
        return Math.addExact(startUs, durationUs);
    }

    private static long samplesAt(long timestampUs) {
        return Math.round(timestampUs * (SUBTITLE_SAMPLE_RATE / 1_000_000.0));
    }

    static long mediaStartUs(FFmpegFrameGrabber grabber) {
        long startUs = grabber.getFormatContext().start_time();
        return startUs == AV_NOPTS_VALUE ? 0 : startUs;
    }

    /** Reads the audio end from the samples when the container omits its duration. */
    static long audioDurationUs(Path videoFile) throws Exception {
        checkInterrupted();
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoFile.toFile())) {
            grabber.setVideoStream(Integer.MAX_VALUE);
            grabber.start();
            long originUs = mediaStartUs(grabber);
            long endUs = 0;
            while (true) {
                checkInterrupted();
                Frame frame = grabber.grabSamples();
                if (frame == null) {
                    return endUs;
                }
                long packetEndUs = frame.timestamp - originUs + samplesPerChannel(frame) * 1_000_000L / frame.sampleRate;
                endUs = Math.max(endUs, packetEndUs);
            }
        }
    }

    private static int samplesPerChannel(Frame frame) {
        int samples = frame.samples[0].remaining();
        return frame.samples.length == 1 ? samples / frame.audioChannels : samples;
    }

    record SubtitleAudio(long startUs, AudioActivity activity) {
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
