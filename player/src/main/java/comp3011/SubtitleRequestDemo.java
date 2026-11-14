package comp3011;

import java.nio.file.Path;
import java.util.List;

public class SubtitleRequestDemo {

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.out.println("Usage: SubtitleRequestDemo <audio-file>");
            return;
        }

        Path audioFile = Path.of(args[0]);

        SubtitleClient client = new SubtitleClient();
        List<SubtitleSegment> segments =
                client.requestSubtitles(audioFile).join();

        System.out.println(
                "Received " + segments.size() + " subtitle segment(s):");

        for (SubtitleSegment segment : segments) {
            System.out.printf(
                    "%.2f - %.2f: %s%n",
                    segment.start(),
                    segment.end(),
                    segment.text()
            );
        }
    }
}