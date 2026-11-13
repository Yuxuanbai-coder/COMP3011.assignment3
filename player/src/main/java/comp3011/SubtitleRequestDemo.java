package comp3011;

import java.nio.file.Path;

public class SubtitleRequestDemo {

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.out.println("Usage: SubtitleRequestDemo <audio-file>");
            return;
        }

        Path audioFile = Path.of(args[0]);

        SubtitleClient client = new SubtitleClient();
        String json = client.sendM4a(audioFile).join();

        System.out.println("Server response:");
        System.out.println(json);
    }
}