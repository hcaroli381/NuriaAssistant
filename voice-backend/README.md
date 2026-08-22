# Alpha Voice Backend (Raspberry Pi 3)

Offline-first voice stack:
1. openWakeWord (wake word)
2. Vosk STT (Spanish small model)
3. Groq LLM bridge (API key only on backend)
4. Piper TTS

## 1) System packages

```bash
sudo apt update
sudo apt install -y python3-venv python3-pip portaudio19-dev libatlas-base-dev alsa-utils
```

Install Piper and download a Spanish neural voice model (`.onnx`) to a local path.

## 2) Python dependencies

```bash
cd voice-backend
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
pip install piper-tts
```

## 3) Configuration

```bash
cp .env.example .env
```

Export variables from `.env` (or set them in a systemd unit). Required minimum:
- `VOSK_MODEL_PATH`
- `GROQ_API_KEY`
- `PIPER_MODEL_PATH`
- `PIPER_BIN` (normally `.venv/bin/piper`)

Optional Spotify controls:
- `SPOTIFY_PLAY_COMMAND` (example: `playerctl -p spotify play`)
- `SPOTIFY_PAUSE_COMMAND` (example: `playerctl -p spotify pause`)

`send_message` action uses:
- `NOTIFY_URL` (default `http://127.0.0.1:8080/notify`)
- `NOTIFY_API_KEY`

With `GROQ_MODEL=groq/compound-mini`, current and time-sensitive questions
can use Groq's built-in web search. No Google API key or browser is required.
The Groq key remains in the backend environment and is never sent to the JavaFX UI.

### Natural Spanish voice

Use a Piper model and its matching `.onnx.json` file, for example:

```env
PIPER_BIN=/full/path/to/voice-backend/.venv/bin/piper
PIPER_MODEL_PATH=/full/path/to/voice-backend/models/piper/es_ES-davefx-medium.onnx
```

If Piper is not installed or fails, the backend falls back to `espeak-ng`, which is
lighter but noticeably more robotic.

Wake word selection:
- `VOICE_WAKEWORD_MODEL_NAME=hey_jarvis` (default)
- Optional custom file: `VOICE_WAKEWORD_MODEL_PATH=/path/to/model.onnx`

## 4) Run

```bash
source .venv/bin/activate
set -a && source .env && set +a
uvicorn main:app --host "${VOICE_BACKEND_HOST:-0.0.0.0}" --port "${VOICE_BACKEND_PORT:-8090}"
```

## 5) API

- `GET /health`
- `GET /assistant/state`
- `POST /assistant/start`
- `POST /assistant/stop`
- `POST /assistant/ask` with body:

```json
{ "text": "que hora es" }
```

`/assistant/start` begins continuous microphone listening + wake-word detection.
