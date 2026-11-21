package comp3011;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlaybackAudioTest {
    private static final int SAMPLE_RATE = 48_000;
    private static final int SAMPLE_COUNT = SAMPLE_RATE / 5;

    @Test
    void mixesSurroundCenterDialogueIntoBothOutputChannels(@TempDir Path directory) throws Exception {
        Path source = writeAudio(directory, 6, 2);

        long[] energy = decodeStereoEnergy(source);

        assertTrue(energy[0] > 1_000_000, "Center-channel speech must reach the left speaker");
        assertTrue(energy[1] > 1_000_000, "Center-channel speech must reach the right speaker");
        assertTrue(Math.abs(energy[0] - energy[1]) <= energy[0] / 100 + 1,
                "Center-channel speech should be balanced between the speakers");
    }

    @Test
    void preservesStereoChannelSeparation(@TempDir Path directory) throws Exception {
        Path source = writeAudio(directory, 2, 0);

        long[] energy = decodeStereoEnergy(source);

        assertTrue(energy[0] > 1_000_000, "The left source channel must remain audible");
        assertEquals(0, energy[1], "A silent right source channel must remain silent");
    }

    @Test
    void playsMonoThroughBothOutputChannels(@TempDir Path directory) throws Exception {
        Path source = writeAudio(directory, 1, 0);

        long[] energy = decodeStereoEnergy(source);

        assertTrue(energy[0] > 1_000_000, "Mono speech must reach the left speaker");
        assertTrue(energy[1] > 1_000_000, "Mono speech must reach the right speaker");
    }

    private static Path writeAudio(Path directory, int channels, int activeChannel) throws Exception {
        byte[] pcm = new byte[SAMPLE_COUNT * channels * Short.BYTES];
        var samples = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        for (int sample = 0; sample < SAMPLE_COUNT; sample++) {
            short signal = (short) (10_000 * Math.sin(2 * Math.PI * 440 * sample / SAMPLE_RATE));
            for (int channel = 0; channel < channels; channel++) {
                samples.put(channel == activeChannel ? signal : (short) 0);
            }
        }
        Path source = directory.resolve("source-" + channels + ".wav");
        AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, channels, true, false);
        try (var audio = new AudioInputStream(new ByteArrayInputStream(pcm), format, SAMPLE_COUNT)) {
            AudioSystem.write(audio, AudioFileFormat.Type.WAVE, source.toFile());
        }
        return source;
    }

    private static long[] decodeStereoEnergy(Path source) throws Exception {
        var output = new ByteArrayOutputStream();
        try (var grabber = VideoPlayerModel.createPlaybackGrabber(source.toFile())) {
            grabber.start();
            Frame frame;
            while ((frame = grabber.grabSamples()) != null) {
                assertEquals(2, frame.audioChannels, "Playback PCM must use stereo output");
                output.writeBytes(AudioPlayer.copySamples(frame));
            }
        }
        byte[] pcm = output.toByteArray();
        assertEquals(SAMPLE_COUNT * 2 * Short.BYTES, pcm.length,
                "Channel conversion must preserve the number of audio frames");
        var samples = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        long[] energy = new long[2];
        while (samples.hasRemaining()) {
            energy[0] += Math.abs((int) samples.get());
            energy[1] += Math.abs((int) samples.get());
        }
        return energy;
    }
}
