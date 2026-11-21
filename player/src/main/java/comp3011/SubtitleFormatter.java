package comp3011;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Formats timed cues on the video's clock. Developed with OpenAI Codex assistance. */
final class SubtitleFormatter {
    private static final int LINE_LENGTH = 42;
    private static final double MIN_CUE_SECONDS = 1;
    private static final double MAX_CUE_SECONDS = 7;
    private static final double PHRASE_GAP_SECONDS = 0.7;

    private SubtitleFormatter() {
    }

    static List<SubtitleSegment> format(
            List<SubtitleSegment> segments, long audioStartUs, long windowStartUs, long windowEndUs) {
        return format(segments, audioStartUs, windowStartUs, windowEndUs, null);
    }

    static List<SubtitleSegment> format(
            List<SubtitleSegment> segments, long audioStartUs, long windowStartUs, long windowEndUs,
            AudioActivity activity) {
        double offset = audioStartUs / 1_000_000.0;
        double windowStart = windowStartUs / 1_000_000.0;
        double windowEnd = windowEndUs / 1_000_000.0;
        List<SubtitleSegment> cues = new ArrayList<>();
        for (SubtitleSegment segment : segments) {
            if (!Double.isFinite(segment.start()) || !Double.isFinite(segment.end())
                    || segment.text() == null || segment.text().isBlank()) {
                continue;
            }
            double start = Math.max(windowStart, offset + Math.max(0, segment.start()));
            double end = Math.min(windowEnd, offset + segment.end());
            if (end <= start) {
                continue;
            }
            List<TimedWord> words = alignWords(segment);
            if (words.isEmpty()) {
                addPages(cues, segment.text(), start, end);
            } else {
                addTimedCues(cues, words, offset, windowStart, windowEnd);
            }
        }

        if (activity != null) {
            cues.removeIf(cue -> !activity.supportsCue(cue.start() - offset, cue.end() - offset));
        }
        cues.sort(Comparator.comparingDouble(SubtitleSegment::start));
        List<SubtitleSegment> result = new ArrayList<>();
        for (int i = 0; i < cues.size(); i++) {
            SubtitleSegment cue = cues.get(i);
            double boundary = i + 1 < cues.size() ? cues.get(i + 1).start() : windowEnd;
            // Extend short cues into available silence without covering the next cue.
            double end = Math.min(boundary,
                    Math.clamp(cue.end(), cue.start() + MIN_CUE_SECONDS, cue.start() + MAX_CUE_SECONDS));
            if (end > cue.start()) {
                result.add(new SubtitleSegment(cue.start(), end, cue.text()));
            }
        }
        return List.copyOf(result);
    }

    private static void addTimedCues(
            List<SubtitleSegment> cues, List<TimedWord> words, double offset, double windowStart, double windowEnd) {
        StringBuilder phrase = new StringBuilder();
        double phraseStart = 0;
        double phraseEnd = 0;
        for (TimedWord word : words) {
            double midpoint = offset + (word.start() + word.end()) / 2;
            if (midpoint < windowStart || midpoint >= windowEnd) {
                continue;
            }
            double start = Math.max(windowStart, offset + word.start());
            double end = Math.min(windowEnd, offset + word.end());
            if (!phrase.isEmpty() && (start - phraseEnd >= PHRASE_GAP_SECONDS
                    || end - phraseStart > MAX_CUE_SECONDS || wrap(phrase.toString() + word.text()).size() > 2)) {
                addPages(cues, phrase.toString(), phraseStart, phraseEnd);
                phrase.setLength(0);
            }
            if (phrase.isEmpty()) {
                phraseStart = start;
                phraseEnd = end;
            } else {
                phraseEnd = Math.max(phraseEnd, end);
            }
            phrase.append(word.text());
        }
        if (!phrase.isEmpty()) {
            addPages(cues, phrase.toString(), phraseStart, phraseEnd);
        }
    }

    /** Maps word times back to the segment spelling, punctuation and spacing. */
    private static List<TimedWord> alignWords(SubtitleSegment segment) {
        if (segment.words() == null || segment.words().isEmpty()) {
            return List.of();
        }
        String text = segment.text().strip();
        List<TimedWord> result = new ArrayList<>();
        SubtitleWord previous = null;
        int displayStart = 0;
        int cursor = 0;
        for (SubtitleWord word : segment.words()) {
            if (word.word() == null || word.word().isBlank() || !Double.isFinite(word.start())
                    || !Double.isFinite(word.end()) || word.start() < 0 || word.end() < word.start()
                    || previous != null && word.start() < previous.start()) {
                return List.of();
            }
            String spoken = word.word().strip();
            int index = findWord(text, spoken, cursor);
            if (index < 0) {
                return List.of();
            }
            if (previous != null) {
                result.add(new TimedWord(previous.start(), previous.end(), text.substring(displayStart, index)));
                displayStart = index;
            }
            previous = word;
            cursor = index + spoken.length();
        }
        // Keep valid word timings when the segment text has an untimed tail.
        boolean untimedTail = text.substring(cursor).codePoints().anyMatch(Character::isLetterOrDigit);
        double end = untimedTail ? Math.max(previous.end(), segment.end()) : previous.end();
        result.add(new TimedWord(previous.start(), end, text.substring(displayStart)));
        return result;
    }

    private static int findWord(String text, String word, int cursor) {
        while (cursor <= text.length() - word.length()) {
            if (text.regionMatches(true, cursor, word, 0, word.length())) {
                return cursor;
            }
            int point = text.codePointAt(cursor);
            if (Character.isLetterOrDigit(point)) {
                break;
            }
            cursor += Character.charCount(point);
        }
        return -1;
    }

    private static void addPages(List<SubtitleSegment> cues, String text, double start, double end) {
        List<String> lines = wrap(text);
        int pages = (lines.size() + 1) / 2;
        double secondsPerPage = (end - start) / pages;
        for (int page = 0; page < pages; page++) {
            int firstLine = page * 2;
            String linesOnPage = String.join("\n", lines.subList(firstLine, Math.min(firstLine + 2, lines.size())));
            cues.add(new SubtitleSegment(start + page * secondsPerPage, start + (page + 1) * secondsPerPage,
                    linesOnPage));
        }
    }

    private static List<String> wrap(String input) {
        String remaining = input.replaceAll("(?U)\\s+", " ").strip();
        List<String> lines = new ArrayList<>();
        while (remaining.codePointCount(0, remaining.length()) > LINE_LENGTH) {
            int limit = remaining.offsetByCodePoints(0, LINE_LENGTH);
            int minPunctuation = remaining.offsetByCodePoints(0, LINE_LENGTH / 2);
            int lastSpace = -1;
            int lastPunctuation = -1;
            for (int index = 0; index < limit;) {
                int point = remaining.codePointAt(index);
                int next = index + Character.charCount(point);
                if (point == ' ') {
                    lastSpace = index;
                } else if (next >= minPunctuation && ".,!?;:。！？、，；：".indexOf(point) >= 0) {
                    lastPunctuation = next;
                }
                index = next;
            }
            int end = limit;
            if (lastPunctuation > 0) {
                end = lastPunctuation;
            } else if (remaining.charAt(limit) != ' ' && lastSpace > 0) {
                end = lastSpace;
            }
            lines.add(remaining.substring(0, end).stripTrailing());
            remaining = remaining.substring(end).stripLeading();
        }
        if (!remaining.isEmpty()) {
            lines.add(remaining);
        }
        return lines;
    }

    private record TimedWord(double start, double end, String text) {
    }
}
