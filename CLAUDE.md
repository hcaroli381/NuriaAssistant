# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) and other AI instances when working with code in this repository.

## Project Architecture

This is a JavaFX desktop application designed to run on a Raspberry Pi 3 with a 1024x600 touchscreen. 
The system operates as an "always-on" smart assistant (similar to an Echo Show) with the following components:

1. **Main JavaFX Application (The Orchestrator):** Handles UI, clock, date display, weather widget, notifications, and Spotify playback view. Designed for 24/7 uptime.
2. **Date & Time System (`ThemeManager`):**
   - Displays real-time clock (`HH:mm:ss`) and the full date with day of the week and month (e.g. `Thursday, 20 August`).
3. **Spotify Connect Integration (`SpotifyService`):**
   - Displays full-screen player with album cover art, song title, artist, album, and playback device badge when playing.
   - Smooth animated Spotify logo transition when music starts playing.
4. **Backend Bridge & Remote Notification System (`NotificationServer`):**
   - Lightweight embedded HTTP server running on port `8080` (`POST /notify`).
   - Authenticated via header `X-API-KEY: nuria-assistant-secret-key`.
   - Displays an animated notification pill at the bottom of the screen with a 10-second auto-hide timeout.
5. **Telegram Bot Integration (`TelegramService`):**
   - Enables sending remote messages to the screen from Telegram on any phone/PC from anywhere in the world.
   - Long-polling daemon using standard Java HTTP Client (zero heavy dependencies, negligible RAM footprint).
   - Sends confirmation reply back to the Telegram chat when the message is displayed on screen.
6. **Weather Service (`WeatherService`):**
   - Fetches current weather data from OpenWeatherMap API for the configured city.

## Remote Messaging System (Telegram & HTTP API)

### 1. Sending messages via Telegram (From anywhere in the world)
1. Talk to `@BotFather` on Telegram to create a bot (send `/newbot`) and copy your Bot Token.
2. Add your token in `config.properties`:
   ```properties
   TELEGRAM_BOT_TOKEN=123456789:ABCdefGHIjklMNOpqrsTUVwxyz
   ```
3. (Optional) Set `TELEGRAM_ALLOWED_CHAT_ID` if you only want your user ID to be allowed.
4. Open your bot in Telegram, tap `/start` and send any message (e.g. *"Hola Nuria! 🥖"*).
5. The message will pop up on the screen and the bot will reply with *"✅ Mensaje mostrado en la pantalla"*.

### 2. Sending messages via local HTTP API (`NotificationServer`)

The application listens on port `8080` for incoming HTTP POST requests at `/notify`.

### Example: Send a remote message from terminal / curl
```bash
curl -X POST http://<IP_OF_RASPBERRY_PI>:8080/notify \
     -H "X-API-KEY: nuria-assistant-secret-key" \
     -d "Hola! No te olvides de comprar pan 🥖"
```

### Example: Send a message via Python
```python
import requests

url = "http://192.168.1.50:8080/notify"  # Replace with Pi's IP
headers = {"X-API-KEY": "nuria-assistant-secret-key"}
data = "Recordatorio: Reunión a las 17:00"

response = requests.post(url, headers=headers, data=data.encode('utf-8'))
print(response.text)
```

## Build and Run

- **Build:** Use standard Maven commands:
  ```bash
  ./mvnw clean package
  ```
- **Run:** Use the JavaFX plugin to launch the application:
  ```bash
  ./mvnw javafx:run
  ```
- **Test:** Run automated unit tests:
  ```bash
  ./mvnw test
  ```

## UI and Theming Guidelines

- **Screen Resolution:** Exactly 1024x600 pixels (Raspberry Pi 3 touchscreen).
- **Layout:** Minimalist top-left information stack:
  1. Clock: Large, high-contrast pure white (`#ffffff`, `96px` bold).
  2. Date: Day of week, day, month in vivid sky blue (`#38bdf8`, `28px` bold).
  3. Weather row: Icon + Temperature (`#ffffff`) + Description (`#e2e8f0`) + City (`#94a3b8`).
- **Background:** Deep Navy Blue ambient radial gradient (`#132a4a` -> `#0a192f` -> `#050d18`).
- **Notification Banner:** Floating amber/gold glassmorphic card (`#fef08a` text on `#0a192f` with gold border).

## Key Files

- `src/main/java/com/example/nuriaassistant/AssistantApplication.java`: Main entry point (configures Scene fill `#0a192f` and attaches `styles.css`).
- `src/main/java/com/example/nuriaassistant/AssistantController.java`: Main JavaFX UI controller.
- `src/main/java/com/example/nuriaassistant/services/ThemeManager.java`: Date and time formatting helpers.
- `src/main/resources/com/example/nuriaassistant/hello-view.fxml`: Main UI layout.
- `src/main/resources/com/example/nuriaassistant/styles.css`: Application stylesheet with Navy Blue palette.
- `src/main/java/com/example/nuriaassistant/spotify/SpotifyService.java`: Spotify Web API & OAuth2 integration.
- `src/main/java/com/example/nuriaassistant/services/WeatherService.java`: OpenWeatherMap client.
- `src/main/java/com/example/nuriaassistant/services/NotificationServer.java`: HTTP server for remote push notifications.

## Spotify/Media Integration

- **Approach:** The Raspberry Pi acts as a Spotify Connect device (using `librespot` or `Raspotify`).
- **UI:** The JavaFX application displays track metadata (title, artist, cover art) by polling the Spotify API.
- **Constraint:** Do not attempt full music control or search within the JavaFX UI. Use the Spotify mobile app/PC client as the primary controller.
- **Authentication:** OAuth2 is required:
  - Redirect URI: `http://127.0.0.1:8888/callback`
  - Client ID and Client Secret can be configured in `config.properties` or environment variables.

## Environment Configuration

- **Configuration file:** `src/main/resources/config.properties` or environment variables:
  - `OPENWEATHER_API_KEY`: API key for weather data.
  - `OPENWEATHER_CITY`: City name (e.g., `Granada,ES`).
  - `SPOTIFY_CLIENT_ID`: Spotify developer application Client ID.
  - `SPOTIFY_CLIENT_SECRET`: Spotify developer application Client Secret.
  - `SPOTIFY_REDIRECT_URI`: OAuth callback URI (default `http://127.0.0.1:8888/callback`).
  - `GEMINI_API_KEY`: Google Gemini API key for the voice assistant and text queries.
  - `TELEGRAM_BOT_TOKEN`: Telegram bot token for remote messaging.
  - `TELEGRAM_ALLOWED_CHAT_ID`: (Optional) Restrict Telegram bot to a single chat ID.

## Gemini Voice Assistant (`VoiceAssistantService`)

### Overview

The voice assistant captures microphone audio and sends it to the Gemini API (with Google Search grounding). It supports two interaction modes:

1. **Hands-free VAD mode** — Automatically detects speech via energy threshold (RMS > 800.0). After 1.4 s of silence it stops capturing and sends the audio to Gemini.
2. **Manual (button toggle) mode** — The on-screen "Gemini Voice" button works as a toggle:
   - **1st press:** starts recording → button label changes to `"Detener 🔴"`, popup shows `"🎙️ Escuchando..."`.
   - **2nd press:** stops recording → audio is sent to Gemini for processing.

### State machine

```
IDLE ──(speech detected / button pressed)──► LISTENING
LISTENING ──(silence timeout / button pressed again)──► PROCESSING
PROCESSING ──(Gemini response received)──► IDLE
```

### Key fields in `VoiceAssistantService`

| Field | Type | Purpose |
|---|---|---|
| `isManualRecording` | `AtomicBoolean` | True while the user is recording via button toggle |
| `forceFinalize` | `AtomicBoolean` | Set to `true` by the second button press to signal the listen loop to process the buffer |
| `manualSpeechBuffer` | `ByteArrayOutputStream` | Accumulates PCM audio during manual recording (separate from the VAD buffer) |
| `SPEECH_ENERGY_THRESHOLD` | `double` | RMS level above which audio is considered speech (default `800.0`) |
| `SILENCE_THRESHOLD_MS` | `int` | Milliseconds of silence before VAD mode finalises a phrase (default `1400 ms`) |

### Important design decisions

- **Manual and VAD buffers are separate.** `manualSpeechBuffer` is used exclusively for button-toggle mode; `speechBuffer` (local to `listenLoop`) is used for hands-free VAD. This prevents cross-contamination between the two modes.
- **Minimum audio length check.** Audio shorter than ~25 000 bytes (~0.8 s at 16 kHz/16-bit mono) is discarded and the state returns to `IDLE` without calling Gemini, to avoid sending noise.
- **Audio format:** 16 kHz, 16-bit, Mono, Signed Little-Endian PCM, wrapped in a standard WAV header before sending to Gemini.
- **TTS response:** After Gemini replies, the text is read aloud via `TextToSpeechService`.

### Key files

- `src/main/java/com/example/nuriaassistant/services/VoiceAssistantService.java`: Microphone capture, VAD, toggle-button logic, PCM→WAV conversion.
- `src/main/java/com/example/nuriaassistant/services/GeminiService.java`: Gemini API client (text and audio queries with Google Search grounding).
- `src/main/java/com/example/nuriaassistant/services/TextToSpeechService.java`: TTS playback of Gemini responses.

