/*
 * COMP3011 Assignment 2 playback-worker communication.
 *
 * Authors:
 *   1. Yuxuan Bai (a2993969), with OpenAI Codex AI assistance.
 */
package comp3011;

/**
 * Immutable notifications sent from the playback worker to the JavaFX thread.
 */
sealed interface PlaybackEvent {
    /** A3 NOTE: Sends a seek preview to JavaFX even when normal timed playback is paused. */
    record PausedFrameReady(PreparedFrame frame) implements PlaybackEvent {
    }

    record SubtitleRendered(PreparedFrame frame, String text, byte[] pixels) implements PlaybackEvent {
    }

    enum FrameCleared implements PlaybackEvent {
        INSTANCE
    }

    enum ShutdownComplete implements PlaybackEvent {
        INSTANCE
    }

    /** A3 NOTE: Publishes video dimensions and duration together so the UI can initialize its timeline. */
    record MediaOpened(int width, int height, long durationUs) implements PlaybackEvent {
    }

    record StatusChanged(String status) implements PlaybackEvent {
    }

    record PlaybackStateChanged(boolean playing, boolean paused) implements PlaybackEvent {
    }

    record AudioOutputStateChanged(boolean enabled) implements PlaybackEvent {
    }
}
