package comp3011;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** A3 NOTE: Uses generated audio and fake asynchronous responses to test preparation without API calls. */
class SubtitleLoaderTest {
    @Test
    // A3 NOTE: Return an empty first response, then verify retry audio starts near the later sustained signal.
    void retriesAnEmptyWindowAfterItsQuietOpening(@TempDir Path directory) throws Exception {
        int rate = 16_000;
        int sampleCount = 8 * rate;
        byte[] pcm = new byte[sampleCount * Short.BYTES];
        pcm[1] = 2;
        for (int sample = 5 * rate; sample < 7 * rate; sample++) {
            pcm[sample * Short.BYTES + 1] = (byte) (sample % 2 == 0 ? 8 : -8);
        }
        Path input = directory.resolve("late-speech.wav");
        try (var audio = new AudioInputStream(new ByteArrayInputStream(pcm),
                new AudioFormat(rate, 16, 1, true, false), sampleCount)) {
            AudioSystem.write(audio, AudioFileFormat.Type.WAVE, input.toFile());
        }

        var uploads = new AtomicInteger();
        var client = new SubtitleClient() {
            @Override
            public CompletableFuture<List<SubtitleSegment>> requestSubtitles(Path clip) throws IOException {
                int request = uploads.incrementAndGet();
                if (request == 1) {
                    return CompletableFuture.completedFuture(List.of());
                }
                try (var audio = AudioSystem.getAudioInputStream(clip.toFile())) {
                    if (audio.getFrameLength() == 54_400) {
                        return CompletableFuture.completedFuture(List.of(new SubtitleSegment(0.9, 1.6, "Later speech")));
                    }
                } catch (Exception e) {
                    throw new IOException("Could not read retry clip", e);
                }
                return CompletableFuture.completedFuture(List.of());
            }
        };

        var loader = new SubtitleLoader(client);
        try {
            long version = loader.setVideo(input.toFile());
            loader.mediaOpened(version, true, 8_000_000L);
            loader.setEnabled(true);
            awaitPreparation(loader);

            assertEquals(new SubtitleLoader.Progress(SubtitleLoader.State.READY, 1, 1), loader.progress());
            assertEquals(2, uploads.get());
            assertEquals("Later speech", loader.textAt(5_700_000L));
        } finally {
            loader.close();
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {130_000_000L, -1})
    // A3 NOTE: A latch holds the first three jobs so concurrency and whole-track preparation can be checked.
    void preparesBeginningMiddleAndEndWhilePlayheadStaysAtTheBeginning(
            long durationUs, @TempDir Path directory) throws Exception {
        int sampleRate = 16_000;
        int samples = 130 * sampleRate;
        Path media = directory.resolve("synthetic.wav");
        byte[] pcm = new byte[samples * 2];
        for (int i = 1; i < pcm.length; i += 2) {
            pcm[i] = (byte) ((i / 32 % 2 == 0) ? 8 : -8);
        }
        try (var audio = new AudioInputStream(new ByteArrayInputStream(pcm),
                new AudioFormat(sampleRate, 16, 1, true, false), samples)) {
            AudioSystem.write(audio, AudioFileFormat.Type.WAVE, media.toFile());
        }

        var requested = new AtomicInteger();
        var firstWorkersSent = new CountDownLatch(3);
        var response = new CompletableFuture<List<SubtitleSegment>>();
        var client = new SubtitleClient() {
            @Override
            public CompletableFuture<List<SubtitleSegment>> requestSubtitles(Path audioFile) {
                requested.incrementAndGet();
                firstWorkersSent.countDown();
                return response;
            }
        };

        var loader = new SubtitleLoader(client);
        try {
            long version = loader.setVideo(media.toFile());
            loader.mediaOpened(version, true, durationUs);
            loader.setEnabled(true);
            assertTrue(firstWorkersSent.await(5, TimeUnit.SECONDS), "The loader did not start the first three windows");
            assertEquals(3, requested.get());
            assertEquals(new SubtitleLoader.Progress(SubtitleLoader.State.LOADING, 0, 7), loader.progress());

            response.complete(List.of(new SubtitleSegment(5.0, 6.0, "Caption")));
            awaitPreparation(loader);

            assertEquals(new SubtitleLoader.Progress(SubtitleLoader.State.READY, 7, 7), loader.progress());
            assertEquals("Caption", loader.textAt(5_500_000L));
            assertEquals("Caption", loader.textAt(62_500_000L));
            assertEquals("Caption", loader.textAt(122_500_000L));
            assertEquals(7, requested.get());
        } finally {
            loader.close();
        }
    }

    /** A3 NOTE: Bounds asynchronous test waiting so a stuck worker becomes a test failure rather than a hang. */
    private static void awaitPreparation(SubtitleLoader loader) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (loader.progress().state() == SubtitleLoader.State.LOADING && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }
}
