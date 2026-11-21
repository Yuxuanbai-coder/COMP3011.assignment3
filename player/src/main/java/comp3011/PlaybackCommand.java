/*
 * COMP3011 Assignment 2 playback-worker communication.
 *
 * Authors:
 *   1. Yuxuan Bai (a2993969), with OpenAI Codex AI assistance.
 */
package comp3011;

import java.io.File;
import java.util.Objects;

/**
 * Immutable commands sent from the JavaFX thread to the playback worker.
 */
sealed interface PlaybackCommand {
    /** A3 NOTE: Carries subtitle version and startup-wait state alongside the selected file. */
    record Play(File file, long subtitleVersion, boolean prepareSubtitles) implements PlaybackCommand {
        public Play {
            Objects.requireNonNull(file, "file");
        }
    }

    /** A3 NOTE: Resumes initial playback only if its subtitle version still identifies the active video. */
    record ResumePrepared(long subtitleVersion) implements PlaybackCommand {
    }

    record SeekTo(long timestampUs) implements PlaybackCommand {
    }

    record SeekBy(long offsetUs) implements PlaybackCommand {
    }

    record AudioFailure(long playbackGeneration, String message) implements PlaybackCommand {
        public AudioFailure {
            Objects.requireNonNull(message, "message");
        }
    }

    enum TogglePause implements PlaybackCommand {
        INSTANCE
    }

    enum ToggleAudio implements PlaybackCommand {
        INSTANCE
    }

    enum Stop implements PlaybackCommand {
        INSTANCE
    }

    enum Shutdown implements PlaybackCommand {
        INSTANCE
    }
}
