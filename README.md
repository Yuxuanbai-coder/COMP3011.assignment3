# COMP3011 Assignment 3

A JavaFX video player with a Spring Boot service that generates English captions for videos containing English speech.

- `player/`: plays local videos, applies video effects, and displays timed captions.
- `server/`: accepts audio, transcribes it through OpenAI, and returns subtitles with timestamps.

## Quick start for macOS

Open two Terminal windows. **Terminal 1 runs the subtitle server. Terminal 2 runs the player.** Copy each command block into the indicated window. The paths below match this Mac; update the project and key paths if using another computer.

| What you need | Location or requirement |
| --- | --- |
| Project | `/Users/yuxuanbai/A3/COMP3011.assignment3` |
| API key | `/Users/yuxuanbai/A3/TEST FILE/key.txt`, containing only the key on one line |
| Java | JDK 25; check with `java -version` |
| Internet | Access to Maven downloads and the OpenAI API, with available API quota |
| Video | A local video containing English speech, such as an MP4 or MKV |

### Step 1: start the subtitle server in Terminal 1

Copy the whole block below. It reads the API key from `key.txt` and starts the server without printing the key.

```bash
cd /Users/yuxuanbai/A3/COMP3011.assignment3

IFS= read -r OPENAI_API_KEY < "../TEST FILE/key.txt"
export OPENAI_API_KEY

sh server/mvnw -f server/pom.xml spring-boot:run 2>&1 | tee /tmp/a3-live-server.log
```

When you see a message containing `Started Assignment1Application`, the server is ready. **Leave this terminal running.** It is normal for the command to keep running without returning to the prompt.

The first run may download Maven and dependencies, so startup can take longer.

### Step 2: open the player in Terminal 2

```bash
cd /Users/yuxuanbai/A3/COMP3011.assignment3

sh server/mvnw -f player/pom.xml javafx:run 2>&1 | tee /tmp/a3-live-player.log
```

The player opens a window where you can choose a video. Audio is enabled by default.

### Step 3: choose a video

1. Save a video to this computer.
2. Click **Open video** in the player, or press **Command+O** on Mac.
3. Select the video in the file picker and open it. File and folder names can contain spaces.
4. The player automatically enables English captions and starts preparing them.
5. Wait for playback and captions. The player initially waits up to about 10 seconds for subtitles, then starts playback while remaining subtitles continue loading in the background. Silent sections or sections without speech may have no captions.

Use **Open video** again to switch videos. Opening a video through the file picker enables captions automatically.

`Preparing subtitles` means preparation is still in progress. `Retrying subtitles` means temporary failures are being retried while captions are enabled. New captions resume automatically when the service recovers. If you see `Subtitles incomplete`, check the troubleshooting section below.

## Video effects

Choose effects when launching the player by adding a `javafx.args` argument. Each example below opens the player with the selected effects. Click **Open video** to choose any local video, including files whose names contain spaces.

### Available effects

| Short option | Long option | What it does |
| --- | --- | --- |
| `-n` | `--number-frames` | Draw the frame number on the video |
| `-s` | `--scratch-frames` | Add vertical film scratches |
| `-f` | `--flicker-frames` | Randomly dim frames to create flicker |
| `-w` | `--black-and-white` | Convert the image to black and white |
| `-y` | `--yellow-frames` | Apply a warmer, yellow colour tone |
| `-v` | `--vignette-frames` | Darken the edges of the image |
| `-d` | `--dust-frames` | Add dust and hair marks |
| `-j` | `--jitter-frames` | Shift frames slightly to create jitter |
| `-m` | `--mottle-frames` | Add uneven, cloudy film mottling |
| `-b` | `--bleed-frames` | Add light bleed |
| `-p` | `--pepper-frames` | Add dark specks and blotches |

**Uppercase `-S` enables subtitles; lowercase `-s` adds scratches.**

### Apply one effect

For black and white, run this from the project root:

```bash
sh server/mvnw -f player/pom.xml javafx:run '-Djavafx.args=-w'
```

Replace `-w` with any short or long option from the table to try another effect. For example, `-v` selects a vignette and `--jitter-frames` selects jitter.

### Combine effects

Black and white with a vignette, scratches, flicker, and dust:

```bash
sh server/mvnw -f player/pom.xml javafx:run '-Djavafx.args=-w -v -s -f -d'
```

A warm colour tone with a vignette, mottling, light bleed, and dark specks:

```bash
sh server/mvnw -f player/pom.xml javafx:run '-Djavafx.args=-y -v -m -b -p'
```

All visual effects, with frame numbers applied last:

```bash
sh server/mvnw -f player/pom.xml javafx:run '-Djavafx.args=-w -y -v -s -f -d -j -m -b -p -n'
```

Short and long options can be mixed. These select the same sequence:

```text
-w -v -d
--black-and-white --vignette-frames --dust-frames
-w --vignette-frames -d
```

### Effect order and repeated effects

Visual effects are applied **from left to right**. Changing their order can change the result. Repeating an option applies that effect again as a separate stage.

Short options can also be grouped. For example, `-jfddn` means jitter, flicker, dust twice, then frame numbering:

```bash
sh server/mvnw -f player/pom.xml javafx:run '-Djavafx.args=-jfddn'
```

This is equivalent to `-j -f -d -d -n`.

The chosen effects stay active when you open another video. To change the effect sequence, close the player and relaunch it with different options. Captions and audio can still be toggled during playback.

The caption frame processor renders white, black-outlined text into the displayed video after the visual effects so the captions stay legible.

## Playback controls

| Control | Action |
| --- | --- |
| **Open video** / `Command+O` | Choose another video and enable English captions |
| **CC** / `S` | Toggle captions |
| `Space` | Pause or resume |
| `Left` / `Right` arrow | Skip backward or forward five seconds |
| Restart button / `Home` | Play from the beginning |
| Click the progress bar | Seek to a time in the video |
| `A` | Toggle audio |
| **Stop** / `Esc` | Exit the player |

If the video becomes unavailable during playback, the player stops at the last visible frame and reports the interruption. Reconnect the drive and resume, or choose a different file with **Open video**.

## Online captions and cache reuse

After closing the player, run this in Terminal 2 to check server usage. You can also run it in a third terminal while the player is open.

```bash
curl -sS -w '\n' http://localhost:8080/api/v1/global/usage
```

A freshly started server should return:

```json
{"usage":0.0}
```

`usage` is the cumulative number of **audio seconds successfully transcribed** by the current server process. It increases when new audio is transcribed online. Overlapping audio segments and retries mean it may differ from the video's duration.

To check caching, wait until subtitles for the whole video are ready, then reopen the same video with **Open video**. Keep the same server process running and compare `usage` before and after replaying: a complete cache hit leaves usage unchanged. Cached tracks can also be reused for renamed videos and excerpts with matching audio.

A response containing `usage: 0` only confirms that the server is reachable. **Captions appearing for a new video, together with increased usage, confirm successful online transcription.** A video with a complete cached track can reuse its existing captions.

When finished, close the player, then press **Ctrl+C** in Terminal 1 to stop the server. Restarting the server resets `usage` to zero.

## Troubleshooting

| Problem | What to do |
| --- | --- |
| `File not found: "/..."` | Launch the player using Step 2, then click **Open video**. Nested quotes in `javafx.args` may become part of the filename. |
| `curl: ... Failed to connect` | Check that Terminal 1 is still running and wait for the server to finish starting. |
| `OPENAI_API_KEY is not configured` | Stop the server in Terminal 1, check that `../TEST FILE/key.txt` contains the key, and repeat the whole command block in Step 1. |
| `Port 8080 was already in use` | Check the previous server terminal. Reuse the correct running service, or press Ctrl+C in its terminal before restarting it. |
| No sound | Press `A` to enable audio. For command-line playback, include `-a`. The player converts surround audio to stereo for compatibility with normal speakers and headphones. |
| No captions / `Subtitles incomplete` | Check errors in both terminals. Confirm that the video contains English speech, captions are enabled, the OpenAI API is reachable, and the key and quota are available. |
| `usage` stays the same when replaying | This is expected when a complete subtitle cache is reused. |
| `Could not resolve host` / Maven cannot download dependencies | Check the network of the computer or execution environment running the command. Run the connection checks below in your Mac Terminal. |

```bash
curl -I https://repo.maven.apache.org/maven2/
curl -I https://api.openai.com/
```

These checks confirm whether an HTTP response can be received. Actual transcription is still needed to verify the API key. Server logs are saved to `/tmp/a3-live-server.log`; player logs are saved to `/tmp/a3-live-player.log`.

## Command-line playback and launch options

To open a video directly, save it as `player/movie.mp4` and run the following from the project root. This example enables audio, captions, black and white, and a vignette. After the player closes, `cd ..` returns the terminal to the project root.

```bash
cd player

sh ../server/mvnw javafx:run '-Djavafx.args=movie.mp4 -a -S -w -v'

cd ..
```

When a video is passed on the command line, audio and captions default to off. Include `-a` and **uppercase `-S`** to enable both. For file paths containing spaces, launch with effect options only and choose the video using **Open video**.

| Short option | Long option | Action |
| --- | --- | --- |
| `-h` | `--help` | Print all supported options |
| `-a` | `--audio` | Enable audio on startup |
| `-S` | `--subtitle-frames` | Enable captions on startup |
| `-x` | `--maximise` | Open the player maximised |
| `-1` | `--monitor-1` | Open on display 1 |
| `-2` | `--monitor-2` | Open on display 2 |

Choose at most one display option. For example, to open a maximised player on display 1 with black and white and a vignette:

```bash
sh server/mvnw -f player/pom.xml javafx:run '-Djavafx.args=-x -1 -w -v'
```

To see all options, run this from the project root:

```bash
cd /Users/yuxuanbai/A3/COMP3011.assignment3

sh server/mvnw -f player/pom.xml javafx:run '-Djavafx.args=--help'
```

## Subtitle cache location

The player sends audio segments to `POST /api/v1/subtitles`. The server uses Whisper to return English captions and timestamps. Completed tracks are saved in `.a3-subtitle-cache/v2/` under the player's working directory. Complete cached tracks can be reused after restarting the player. Launch it from the same directory to reuse the same cache location.

## Automated tests on macOS

Run these commands from the project root:

```bash
cd /Users/yuxuanbai/A3/COMP3011.assignment3

sh server/mvnw -f player/pom.xml test
sh server/mvnw -f server/pom.xml test
```

Both modules should report `BUILD SUCCESS`. Use the quick-start steps above to check actual video playback and online captions separately.

To print frame rate and per-stage processing times while using effects:

```bash
VIDEOPLAYER_PROFILE=true sh server/mvnw -f player/pom.xml javafx:run '-Djavafx.args=-w -v -d'
```

## Alternative: Windows PowerShell

JDK 25 is also required on Windows. Open two terminals and change to the Windows project root in both. If needed, configure Java first:

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-25'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
java -version
```

In Terminal 1, assuming the key is also saved in `TEST FILE/key.txt` next to the project:

```powershell
$env:OPENAI_API_KEY = (Get-Content -Raw '..\TEST FILE\key.txt').Trim()
.\server\mvnw.cmd -f .\server\pom.xml spring-boot:run
```

In Terminal 2:

```powershell
.\server\mvnw.cmd -f .\player\pom.xml javafx:run
```

To start with effects instead, use the same option names as on macOS:

```powershell
.\server\mvnw.cmd -f .\player\pom.xml javafx:run '-Djavafx.args=-w -v -s -f -d'
```

Click **Open video** to choose a video. The Windows shortcut is **Ctrl+O**. Check server usage with:

```powershell
Invoke-RestMethod http://localhost:8080/api/v1/global/usage
```

## Package for TITAN using Windows PowerShell

Build the executable server JAR from the repository root.

```powershell
.\server\mvnw.cmd -f .\server\pom.xml package
```

Run the JAR locally with `java -jar .\server\target\Assignment1-0.0.1-SNAPSHOT.jar` after setting `OPENAI_API_KEY` in that terminal. For TITAN, stage the JAR alongside the player POM and source directory.

```powershell
$stage = Join-Path $PWD ('target\TITAN-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $stage -Force | Out-Null
Copy-Item -LiteralPath .\server\target\Assignment1-0.0.1-SNAPSHOT.jar -Destination "$stage\A3-subtitle-server.jar"
Copy-Item -LiteralPath .\player\pom.xml -Destination $stage
Copy-Item -LiteralPath .\player\src -Destination $stage -Recurse
Compress-Archive -LiteralPath "$stage\A3-subtitle-server.jar","$stage\pom.xml","$stage\src" -DestinationPath "$stage.zip"
```

Upload the resulting ZIP from `target/`. Its root contains exactly these three entries.

```text
A3-subtitle-server.jar
pom.xml
src/
```

TITAN supplies the test media. Keep API keys and local videos such as `test.mp4` outside the submission.

## Source and attribution

The project builds on the original COMP3011 materials from Simon Ratcliffe, in collaboration with GPT-5.6 Terra, and on Yuxuan Bai's Assignments 1 and 2. Source files retain their original attribution.

OpenAI Codex assisted with the subtitle pipeline and its tests, including PCM extraction, audio fingerprints, cache lookup, caption rendering, and recovery after temporary service or media failures.

- Assignment 1 source commit `c88e91084487b4b64c0706435427f9a77b6a1658`
- Assignment 2 source commit `1ac5c42286597f588afee3d521b8049dadd5b462`
