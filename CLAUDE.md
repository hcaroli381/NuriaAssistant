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
  - `TELEGRAM_BOT_TOKEN`: Telegram bot token for remote messaging.
  - `TELEGRAM_ALLOWED_CHAT_ID`: (Optional) Restrict Telegram bot to a single chat ID.

## Voice Assistant Backend (Python on Raspberry Pi)

The JavaFX app currently focuses on display/orchestration. Voice runs in `voice-backend/` as a separate FastAPI service:

1. `openWakeWord` for continuous wake-word detection.
2. `Vosk` offline STT using the Spanish small model.
3. Groq OpenAI-compatible chat completion as the LLM backend.
4. `Piper` for offline neural TTS playback.

Current working configuration:
- Wake word: `hey_jarvis` using an explicit ONNX model path.
- Groq model: `groq/compound-mini`, which can perform built-in web search for current questions without Google APIs or a browser.
- TTS: Piper Spanish neural voice (`es_ES-davefx-medium.onnx`); `espeak-ng` is only a fallback when the Piper executable is unavailable.
- The runtime listens only after `POST /assistant/start`.

### Security model

- Keep `GROQ_API_KEY` only in the backend process environment.
- Do not place LLM keys in Java client files, FXML, or front-end code.

### Backend tool actions

The backend can emit/execute structured actions:
- `get_time`
- `send_message` (uses existing local `NotificationServer` `/notify`)
- `spotify_play` / `spotify_pause` (shell commands configured via env)

### Backend files

- `voice-backend/main.py`: FastAPI app + wake word/STT/LLM/TTS runtime loop.
- `voice-backend/.env.example`: Runtime configuration template.
- `voice-backend/requirements.txt`: Python dependencies.

### Voice troubleshooting

- Check backend: `curl http://127.0.0.1:8090/health`
- Start microphone loop: `curl -X POST http://127.0.0.1:8090/assistant/start`
- Inspect wake/transcription state: `curl http://127.0.0.1:8090/assistant/state`
- If Piper is missing, install `piper-tts` in the backend venv and set `PIPER_BIN` to the venv executable. The fallback `espeak-ng` is functional but sounds more robotic.
