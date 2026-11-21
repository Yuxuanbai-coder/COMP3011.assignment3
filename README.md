# COMP3011 Assignment 3

A JavaFX video player with automatic English captions and a Spring Boot subtitle service.

- `player/` plays local videos, applies video effects, and displays timed captions.
- `server/` accepts audio uploads and returns English subtitles with timestamps.

## Requirements

- JDK 25.
- A local video with English speech to try automatic captions.
- Network access and an OpenAI API key to transcribe new audio. The Maven Wrapper also downloads Maven and build dependencies on the first run.

The examples use Windows PowerShell. Open two terminals at the repository root. If `JAVA_HOME` is unset or points to a different JDK, replace the example path with your JDK 25 directory in that terminal.

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-25'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
java -version
```

## Run locally

### 1. Start the subtitle server

Set `OPENAI_API_KEY` in the server terminal and leave it open while using the player.

```powershell
$env:OPENAI_API_KEY = 'your-openai-api-key'
.\server\mvnw.cmd -f .\server\pom.xml spring-boot:run
```

Once the server is running, check its usage endpoint from the second terminal.

```powershell
Invoke-RestMethod http://localhost:8080/api/v1/global/usage
```

`usage` reports the cumulative seconds of audio transcribed by the current server process. A freshly started server reports `0`.

### 2. Open a video in the player

```powershell
.\server\mvnw.cmd -f .\player\pom.xml javafx:run
```

Choose **Open video** and select a local file such as an MP4 or MKV. This enables English captions for that video. The player waits up to 10 seconds for subtitles before starting playback. Any remaining captions are prepared in the background.

| Control | Action |
| --- | --- |
| `Ctrl+O` | Open another local video |
| **CC** or `S` | Toggle captions |
| `Space` | Pause or resume |
| `←` / `→` | Skip backward or forward five seconds |
| `Home` | Restart from the beginning |
| Progress bar | Seek to a time in the video |
| `A` | Toggle audio |
| `Esc` or **Stop** | Exit the player |

Press `Ctrl+C` in the server terminal when finished.

## Launch a video from the command line

Place a local `movie.mp4` in `player/` for this example, then run the following from the repository root.

```powershell
Push-Location .\player
..\server\mvnw.cmd javafx:run '-Djavafx.args=movie.mp4 -a -Sn'
Pop-Location
```

Captions default to off for a video passed on the command line. `-a` enables audio, uppercase `-S` enables subtitles on startup, and `-n` adds frame numbers. Run `--help` to see all playback and effect options.

```powershell
.\server\mvnw.cmd -f .\player\pom.xml javafx:run '-Djavafx.args=--help'
```

## Subtitle cache

The player decodes the audio once and sends timed WAV segments to `POST /api/v1/subtitles`. The server uses Whisper to transcribe English speech and returns timestamps for subtitle placement.

Completed tracks are saved in `.a3-subtitle-cache/v1/` under the player's working directory. Replaying or renaming a video can reuse its subtitles, and a matching excerpt can reuse the corresponding part of the track. Cache hits leave the server's usage counter unchanged.

## Run the tests

```powershell
.\server\mvnw.cmd -f .\player\pom.xml test
.\server\mvnw.cmd -f .\server\pom.xml test
```

## Package for TITAN

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

The project builds on the original COMP3011 materials from Simon Ratcliffe, in collaboration with GPT-5.6 Terra, and on Yuxuan Bai's Assignments 1 and 2. Source files retain their original attribution and acknowledgements of OpenAI Codex assistance.

- Assignment 1 source commit `c88e91084487b4b64c0706435427f9a77b6a1658`
- Assignment 2 source commit `1ac5c42286597f588afee3d521b8049dadd5b462`
