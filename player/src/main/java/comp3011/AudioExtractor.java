package comp3011;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import static org.bytedeco.ffmpeg.global.avutil.AV_NOPTS_VALUE;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;

/** A3 NOTE: Decodes one 16 kHz mono PCM track, then copies lossless WAV windows for subtitle requests. */
public class AudioExtractor {
    static final int SUBTITLE_SAMPLE_RATE = 16_000;
    private static final int TIMESTAMP_ROUNDING_SAMPLES = SUBTITLE_SAMPLE_RATE / 500;

    /** A3 NOTE: Decodes once for all workers; inserted silence keeps sample offsets on the video's timeline. */
    static long decodeSubtitleAudio(Path videoFile, Path pcmFile, long durationUs)
            throws Exception {
        long sampleLimit = durationUs > 0 ? samplesAt(durationUs) : Long.MAX_VALUE;
        byte[] bytes = new byte[16_384];
        byte[] silence = new byte[bytes.length];
        ShortBuffer transfer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        long filledUntil = 0;
        checkInterrupted();
        try (var grabber = new FFmpegFrameGrabber(videoFile.toFile());
                var output = new BufferedOutputStream(Files.newOutputStream(pcmFile, StandardOpenOption.CREATE_NEW))) {
            grabber.setVideoStream(Integer.MAX_VALUE);
            grabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
            grabber.setAudioChannels(1);
            grabber.setSampleRate(SUBTITLE_SAMPLE_RATE);
            grabber.start();
            long originUs = mediaStartUs(grabber);
            Frame frame;
            while ((frame = grabber.grabSamples()) != null) {
                checkInterrupted();
                long offset = samplesAt(frame.timestamp - originUs);
                if (offset >= sampleLimit) {
                    break;
                }
                if (filledUntil > 0 && Math.abs(offset - filledUntil) <= TIMESTAMP_ROUNDING_SAMPLES) {
                    // Container timestamps can round consecutive PCM frames to slightly different boundaries.
                    offset = filledUntil;
                }
                ShortBuffer samples = (ShortBuffer) frame.samples[0];
                long destination = Math.max(filledUntil, offset);
                long skip = destination - offset;
                long count = Math.min(sampleLimit - destination, samples.remaining() - skip);
                if (count > 0) {
                    while (filledUntil < destination) {
                        checkInterrupted();
                        int zeroSamples = (int) Math.min(transfer.capacity(), destination - filledUntil);
                        output.write(silence, 0, zeroSamples * Short.BYTES);
                        filledUntil += zeroSamples;
                    }
                    int sourceIndex = samples.position() + (int) skip;
                    while (count > 0) {
                        int copy = (int) Math.min(transfer.capacity(), count);
                        transfer.put(0, samples, sourceIndex, copy);
                        output.write(bytes, 0, copy * Short.BYTES);
                        sourceIndex += copy;
                        filledUntil += copy;
                        count -= copy;
                    }
                }
            }
        }
        checkInterrupted();
        return Math.round(filledUntil * (1_000_000.0 / SUBTITLE_SAMPLE_RATE));
    }

    /** A3 NOTE: Copies a microsecond-based window to WAV and measures its audio activity without re-decoding. */
    static Optional<SubtitleAudio> writeSubtitleChunk(Path pcmFile, Path audioFile, long startUs, long durationUs)
            throws IOException, InterruptedException {
        validateWindow(audioFile, startUs, durationUs);
        int sampleCount = Math.toIntExact(samplesAt(durationUs));
        byte[] bytes = new byte[Math.multiplyExact(sampleCount, Short.BYTES)];
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        try (var source = FileChannel.open(pcmFile, StandardOpenOption.READ)) {
            source.position(samplesAt(startUs) * Short.BYTES);
            while (buffer.hasRemaining() && source.read(buffer) > 0) {
                checkInterrupted();
            }
        }
        if (buffer.position() == 0) {
            return Optional.empty();
        }
        ShortBuffer pcm = buffer.rewind().asShortBuffer();
        AudioFormat format = new AudioFormat(SUBTITLE_SAMPLE_RATE, 16, 1, true, false);
        try (var audio = new AudioInputStream(new ByteArrayInputStream(bytes), format, sampleCount)) {
            AudioSystem.write(audio, AudioFileFormat.Type.WAVE, audioFile.toFile());
        }
        return Optional.of(new SubtitleAudio(startUs, AudioActivity.fromPcm(pcm, SUBTITLE_SAMPLE_RATE)));
    }

    /** A3 NOTE: Rejects invalid/overflowing windows and prevents overwriting an existing output file. */
    private static void validateWindow(Path audioFile, long startUs, long durationUs) {
        if (startUs < 0 || durationUs <= 0) {
            throw new IllegalArgumentException("Invalid audio extraction window");
        }
        if (Files.exists(audioFile)) {
            throw new IllegalArgumentException("Output file already exists: " + audioFile);
        }
        Math.addExact(startUs, durationUs);
    }

    /** A3 NOTE: Converts media microseconds to sample positions at the fixed subtitle sample rate. */
    private static long samplesAt(long timestampUs) {
        return Math.round(timestampUs * (SUBTITLE_SAMPLE_RATE / 1_000_000.0));
    }

    /** A3 NOTE: Removes a container's nonzero timestamp origin so playback and subtitle time both start at zero. */
    static long mediaStartUs(FFmpegFrameGrabber grabber) {
        long startUs = grabber.getFormatContext().start_time();
        return startUs == AV_NOPTS_VALUE ? 0 : startUs;
    }

    /** A3 NOTE: Couples the clip's media offset with the activity mask used to validate returned cue times. */
    record SubtitleAudio(long startUs, AudioActivity activity) {
    }

    /** A3 NOTE: Lets video changes and shutdown cancel lengthy native decode/copy loops cooperatively. */
    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException();
        }
    }
}
