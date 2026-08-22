# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) and other AI instances when working with code in this repository.

## Naming

The assistant's public/persona name is **Alpha** (never "Nuria"). User-facing strings, prompts and UI labels must say Alpha. Internal identifiers (Java packages `com.example.nuriaassistant`, systemd unit names, repo folder, API key value) intentionally keep the legacy naming to avoid breaking deployments — do not rename them casually.

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
   - Shows an amber/gold notification pill at the bottom of the screen that persists until tapped on-screen or cleared via Telegram `/clear`.
5. **Telegram Bot Integration (`TelegramService`):**
   - Enables sending remote messages to the screen from Telegram on any phone/PC from anywhere in the world.
   - Long-polling daemon using standard Java HTTP Client (zero heavy dependencies, negligible RAM footprint).
   - Sends confirmation reply back to the Telegram chat when the message is displayed on screen.
6. **Weather Service (`WeatherService`):**
   - Fetches current weather data from OpenWeatherMap API for the configured city.
8. **Alarm System (`AlarmService`, tactile only for now):**
   - Alarms persist as zero-dependency JSON at `~/.alpha/alarms.json` (atomic writes, survives reboots).
   - Checked every second from the existing clock tick; a 30-second fire window prevents skipped rings under load.
   - Full-screen ring overlay (purple radial gradient + pulsing orange glow) with big time display and two large touch buttons: *Posponer 5 min* and *Apagar*.
   - Looping WAV chime (`sounds/alarm.wav`) via JavaFX `MediaPlayer` — no new dependencies.
   - Full-screen touch manager sheet (clock icon button next to the top-right mic orb): list sorted by time, ON/OFF toggle, delete ✕, tap row to edit, add with hour/minute steppers, *Solo una vez* / *Repetir* mode selector and L M X J V S D day chips (empty selection = daily).
   - Subtle next-alarm hint under the weather row ("Hoy 07:00", "07:00 en 12 min", "Posponer ...").
9. **Voice Assistant Front-End (`VoiceAssistantService`):**
   - Live UI mirror of the Python voice backend, polled every second on a dedicated daemon thread.
   - Ambient mic orb (top-right, doubles as touch mute button) + expandable glassmorphic conversation card.
   - Colors mirror the backend LED ring semantics: sky-blue idle, green listening, amber processing, purple speaking, red error.
   - Auto-starts the backend runtime when port 8090 is reachable; auto-start is suppressed while muted (15s retry throttle).
   - Fully hidden while Spotify full-screen mode is active (assistant keeps running, nothing renders).
   - Performance-first for Pi 3: change-deduplicated state machine, animations created once and reused, transform/opacity only, 5s poll backoff when the backend is unreachable.

## Remote Messaging System (Telegram & HTTP API)

### 1. Sending messages via Telegram (From anywhere in the world)
1. Talk to `@BotFather` on Telegram to create a bot (send `/newbot`) and copy your Bot Token.
2. Add your token in `config.properties`:
   ```properties
   TELEGRAM_BOT_TOKEN=123456789:ABCdefGHIjklMNOpqrsTUVwxyz
   ```
3. (Optional) Set `TELEGRAM_ALLOWED_CHAT_ID` if you only want your user ID to be allowed.
4. Open your bot in Telegram, tap `/start` and send any message (e.g. *"Hola Alpha! 🥖"*).
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
- `src/main/java/com/example/nuriaassistant/services/VoiceAssistantService.java`: HTTP client for the voice backend (state polling, start/stop, zero-dependency JSON parsing).
- `src/main/java/com/example/nuriaassistant/models/VoiceAssistantSnapshot.java`: Immutable record of the backend runtime state.
- `src/main/java/com/example/nuriaassistant/models/Alarm.java`: Alarm model (time, repeat days, snooze/fire state, due-window logic).
- `src/main/java/com/example/nuriaassistant/services/AlarmService.java`: Alarm persistence (`~/.alpha/alarms.json`), due-checks and next-alarm summary.
- `src/main/resources/com/example/nuriaassistant/sounds/alarm.wav`: Looping ring chime.
- `deploy/`: systemd units (`nuria-voice.service`, `nuria-assistant.service`) + `install.sh` for boot-on-power kiosk deployment.

## Alarms

- **Storage:** `~/.alpha/alarms.json`. Schema:
  ```json
  [
    {
      "id": "uuid",
      "hour": 7,
      "minute": 30,
      "enabled": true,
      "label": "Despertar",
      "repeatDays": ["MONDAY", "TUESDAY"],
      "once": false,
      "lastFiredDate": "2026-08-22",
      "snoozeUntil": null
    }
  ]
  ```
- Empty `repeatDays` = daily. `lastFiredDate` prevents double-rings within the same day; `snoozeUntil` re-rings after *Posponer* (+5 min).
- `"once": true` alarms ring a single time and are auto-deleted when dismissed (*Solo una vez* mode in the editor); snoozing one keeps it until the snoozed ring is dismissed.
- **Tactile only for now:** set alarms from the on-screen sheet (clock icon button beside the top-right mic orb). Voice (`set_alarm`) and Telegram `/alarma HH:mm` commands are planned follow-ups — wire them through a future `POST /alarm` endpoint on `NotificationServer`.
- **Z-order gotcha:** full-screen visible panes (e.g. `voiceOverlayLayer`) swallow touches beneath them — interactive controls must live in a layer at or above the overlays that are visible simultaneously (this is why the alarm clock button lives inside `voiceOverlayLayer`, and the notification banner sits above it as its own topmost layer).

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
  - `VOICE_BACKEND_URL`: Base URL of the Python voice backend (default `http://127.0.0.1:8090`).

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
- `voice-backend/models/`: Downloaded models (gitignored): `vosk/vosk-model-small-es-0.42`, `hey_jarvis_v0.1.onnx`, `piper/es_ES-davefx-medium.onnx`.

### Backend runtime behavior

- `.env` is loaded via `python-dotenv` relative to `main.py` itself, so uvicorn can be launched from any working directory.
- Relative model paths (`VOSK_MODEL_PATH`, `PIPER_MODEL_PATH`, `VOICE_WAKEWORD_MODEL_PATH`) are resolved against the backend directory, keeping the same `.env` portable across machines.
- **Pre-speech timeout** (`VOICE_PRE_SPEECH_TIMEOUT_SECONDS`, default 2.5s): if the wake word fires but nobody starts speaking, capture ends and the runtime returns to idle instead of waiting the full 8s speech window.
- **Post-reply echo flush** (`VOICE_POST_REPLY_FLUSH_SECONDS`, default 0.8s): buffered mic audio (which contains the TTS echo of Alpha's own voice) is discarded after every answer to prevent self-triggered listening cycles.
- Muting from the front-end simply calls `POST /assistant/stop` (mic off, models stay loaded); unmuting calls `/assistant/start`. The JavaFX auto-start never fights a manual mute.

### Boot-on-power deployment (Raspberry Pi)

```bash
cd deploy && ./install.sh $USER          # renders placeholders and installs both systemd units
```

- `nuria-voice.service`: backend at boot, `Restart=always` (5s backoff), bound to `0.0.0.0:8090`.
- `nuria-assistant.service`: waits for X display `:0` (XWayland on Pi OS Bookworm), then runs `./mvnw javafx:run`; requires the voice service.
- The JavaFX app auto-starts the runtime loop once the backend answers `/assistant/state`, so no manual step is needed after power-on.

### Voice troubleshooting

- Check backend: `curl http://127.0.0.1:8090/health`
- Start microphone loop: `curl -X POST http://127.0.0.1:8090/assistant/start`
- Inspect wake/transcription state: `curl http://127.0.0.1:8090/assistant/state`
- App console diagnostics: `Voice backend reachable; polling live.` on first contact, then `...runtime stopped. Auto-starting...` within ~15s; failed start/stop requests are now logged instead of swallowed.
- Grey orb = backend unreachable from the app (auto-start can only fire once polls succeed). If it persists, check the service: `systemctl status nuria-voice`.
- If Piper is missing, install `piper-tts` in the backend venv and set `PIPER_BIN` to the venv executable. The fallback `espeak-ng` is functional but sounds more robotic.
- If the assistant "keeps listening" in noisy rooms (music playing), raise `VOICE_RMS_THRESHOLD` (e.g., 800–1200).
