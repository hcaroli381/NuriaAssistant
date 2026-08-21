from __future__ import annotations

import json
import os
import shlex
import shutil
import subprocess
import tempfile
import threading
import time
from dataclasses import dataclass, asdict
from datetime import datetime
from enum import Enum
from pathlib import Path
from typing import Any

import httpx
import numpy as np
import sounddevice as sd
from fastapi import FastAPI, HTTPException
from openwakeword.model import Model as OpenWakeWordModel
from openwakeword.utils import download_models as openwakeword_download_models
from pydantic import BaseModel, Field
from vosk import KaldiRecognizer, Model as VoskModel


def _env(key: str, default: str) -> str:
    value = os.getenv(key)
    if value is None or not value.strip():
        return default
    return value.strip()


def _env_int(key: str, default: int) -> int:
    return int(_env(key, str(default)))


def _env_float(key: str, default: float) -> float:
    return float(_env(key, str(default)))


@dataclass
class Settings:
    host: str = _env("VOICE_BACKEND_HOST", "0.0.0.0")
    port: int = _env_int("VOICE_BACKEND_PORT", 8090)

    sample_rate: int = _env_int("VOICE_SAMPLE_RATE", 16000)
    channels: int = _env_int("VOICE_CHANNELS", 1)
    block_size: int = _env_int("VOICE_BLOCK_SIZE", 1280)
    wake_threshold: float = _env_float("VOICE_WAKE_THRESHOLD", 0.45)
    rms_threshold: float = _env_float("VOICE_RMS_THRESHOLD", 500.0)
    silence_seconds: float = _env_float("VOICE_SILENCE_SECONDS", 1.2)
    min_speech_seconds: float = _env_float("VOICE_MIN_SPEECH_SECONDS", 0.7)
    max_speech_seconds: float = _env_float("VOICE_MAX_SPEECH_SECONDS", 8.0)
    wake_cooldown_seconds: float = _env_float("VOICE_WAKE_COOLDOWN_SECONDS", 2.5)

    wakeword_model_path: str = _env("VOICE_WAKEWORD_MODEL_PATH", "")
    wakeword_model_name: str = _env("VOICE_WAKEWORD_MODEL_NAME", "hey_jarvis")
    vosk_model_path: str = _env("VOSK_MODEL_PATH", "")

    groq_api_key: str = _env("GROQ_API_KEY", "")
    groq_model: str = _env("GROQ_MODEL", "llama-3.1-8b-instant")
    groq_base_url: str = _env("GROQ_BASE_URL", "https://api.groq.com/openai/v1")
    groq_timeout_seconds: int = _env_int("GROQ_TIMEOUT_SECONDS", 20)

    piper_model_path: str = _env("PIPER_MODEL_PATH", "")
    piper_bin: str = _env("PIPER_BIN", "piper")
    piper_play_command: str = _env("PIPER_PLAY_COMMAND", "aplay -q")

    spotify_play_command: str = _env("SPOTIFY_PLAY_COMMAND", "")
    spotify_pause_command: str = _env("SPOTIFY_PAUSE_COMMAND", "")
    notify_url: str = _env("NOTIFY_URL", "http://127.0.0.1:8080/notify")
    notify_api_key: str = _env("NOTIFY_API_KEY", "nuria-assistant-secret-key")

    led_enabled: bool = _env("LED_ENABLED", "false").lower() == "true"
    led_pin: int = _env_int("LED_PIN", 18)
    led_count: int = _env_int("LED_COUNT", 12)
    led_brightness: int = _env_int("LED_BRIGHTNESS", 80)


class AssistantState(str, Enum):
    idle = "idle"
    listening = "listening"
    processing = "processing"
    speaking = "speaking"
    stopped = "stopped"
    error = "error"


@dataclass
class RuntimeState:
    state: AssistantState = AssistantState.stopped
    wake_word_score: float = 0.0
    wake_word_model: str = ""
    last_transcript: str = ""
    last_reply: str = ""
    last_action: str = "none"
    last_error: str = ""
    last_event_at: float = 0.0
    running: bool = False


class AskRequest(BaseModel):
    text: str = Field(min_length=1, max_length=3000)


class StartResponse(BaseModel):
    started: bool
    message: str


class StopResponse(BaseModel):
    stopped: bool
    message: str


SYSTEM_PROMPT = """You are Nuria, a concise Spanish home assistant.
You can return one optional action + a spoken reply.

Allowed actions:
- none
- get_time
- send_message (arg: message)
- spotify_play
- spotify_pause

For current, recent, factual, or time-sensitive questions, use your built-in web search before answering.
Output JSON only with this exact shape:
{
  "spoken_reply": "texto en espanol",
  "action": {
    "name": "none|get_time|send_message|spotify_play|spotify_pause",
    "args": {}
  }
}
"""


class VoiceAssistantRuntime:
    def __init__(self, settings: Settings):
        self.settings = settings
        self.state = RuntimeState()
        self._state_lock = threading.Lock()
        self._thread: threading.Thread | None = None
        self._stop_event = threading.Event()
        self._model = self._load_wakeword_model()
        self._vosk_model = self._load_vosk_model()
        self._client = httpx.Client(timeout=self.settings.groq_timeout_seconds)
        self._led = LedRingController(settings) if settings.led_enabled else None

    def _load_wakeword_model(self) -> OpenWakeWordModel:
        # Prefer ONNX to avoid hard dependency on tflite-runtime on some platforms.
        if self.settings.wakeword_model_path:
            model_path = Path(self.settings.wakeword_model_path)
            if not model_path.exists():
                raise FileNotFoundError(f"Wake word model path not found: {model_path}")
            return OpenWakeWordModel(
                wakeword_models=[str(model_path)],
                inference_framework="onnx" if str(model_path).endswith(".onnx") else "tflite",
            )

        model_name = self.settings.wakeword_model_name or "hey_jarvis"
        try:
            return OpenWakeWordModel(
                wakeword_models=[model_name],
                inference_framework="onnx",
            )
        except Exception:
            # Missing packaged resources are common in some installs; fetch only needed models.
            openwakeword_download_models(model_names=[model_name])
            return OpenWakeWordModel(
                wakeword_models=[model_name],
                inference_framework="onnx",
            )

    def _load_vosk_model(self) -> VoskModel:
        if not self.settings.vosk_model_path:
            raise ValueError("VOSK_MODEL_PATH is required.")
        model_path = Path(self.settings.vosk_model_path)
        if not model_path.exists():
            raise FileNotFoundError(f"VOSK model path not found: {model_path}")
        return VoskModel(str(model_path))

    def start(self) -> tuple[bool, str]:
        if self._thread and self._thread.is_alive():
            return False, "Assistant runtime is already running."

        self._stop_event.clear()
        self._set_state(AssistantState.idle, running=True, last_error="")
        self._thread = threading.Thread(target=self._run_loop, name="voice-runtime", daemon=True)
        self._thread.start()
        return True, "Assistant runtime started."

    def stop(self) -> tuple[bool, str]:
        if not self._thread or not self._thread.is_alive():
            self._set_state(AssistantState.stopped, running=False)
            return False, "Assistant runtime is not running."

        self._stop_event.set()
        self._thread.join(timeout=4)
        self._set_state(AssistantState.stopped, running=False)
        return True, "Assistant runtime stopped."

    def snapshot(self) -> dict[str, Any]:
        with self._state_lock:
            payload = asdict(self.state)
        payload["last_event_at_iso"] = (
            datetime.fromtimestamp(payload["last_event_at"]).isoformat() if payload["last_event_at"] else ""
        )
        return payload

    def ask_text(self, text: str) -> dict[str, Any]:
        return self._process_text(text)

    def _set_state(self, new_state: AssistantState, running: bool | None = None, **updates: Any) -> None:
        with self._state_lock:
            state_changed = self.state.state != new_state
            self.state.state = new_state
            if running is not None:
                self.state.running = running
            self.state.last_event_at = time.time()
            for k, v in updates.items():
                setattr(self.state, k, v)
        if state_changed and self._led is not None:
            self._led.set_state(new_state)

    def _run_loop(self) -> None:
        last_wake_ts = 0.0
        try:
            with sd.RawInputStream(
                samplerate=self.settings.sample_rate,
                blocksize=self.settings.block_size,
                channels=self.settings.channels,
                dtype="int16",
            ) as stream:
                while not self._stop_event.is_set():
                    chunk, overflowed = stream.read(self.settings.block_size)
                    if overflowed:
                        self._set_state(AssistantState.error, last_error="Audio overflow on input stream.")
                    audio_np = np.frombuffer(chunk, dtype=np.int16)

                    score_name, score_value = self._wakeword_score(audio_np)
                    self._set_state(
                        AssistantState.idle,
                        wake_word_score=score_value,
                        wake_word_model=score_name,
                    )

                    now = time.monotonic()
                    can_wake = now - last_wake_ts > self.settings.wake_cooldown_seconds
                    if score_value >= self.settings.wake_threshold and can_wake:
                        last_wake_ts = now
                        frames = self._capture_utterance(stream, bytes(chunk))
                        transcript = self._transcribe(frames)
                        if transcript:
                            try:
                                self._process_text(transcript)
                            except Exception as exc:
                                self._set_state(AssistantState.error, last_error=f"Processing error: {exc}")
        except Exception as exc:
            self._set_state(AssistantState.error, running=False, last_error=str(exc))

    def _wakeword_score(self, audio_np: np.ndarray) -> tuple[str, float]:
        prediction = self._model.predict(audio_np)
        best_name = ""
        best_value = 0.0

        if isinstance(prediction, dict):
            for name, value in prediction.items():
                score = self._extract_score(value)
                if score > best_value:
                    best_name = name
                    best_value = score
        return best_name, best_value

    @staticmethod
    def _extract_score(value: Any) -> float:
        if isinstance(value, (float, int, np.floating, np.integer)):
            return float(value)
        if isinstance(value, list) and value:
            return float(value[-1])
        if isinstance(value, np.ndarray) and value.size > 0:
            return float(value.flatten()[-1])
        return 0.0

    def _capture_utterance(self, stream: sd.RawInputStream, first_chunk: bytes) -> list[bytes]:
        self._set_state(AssistantState.listening)
        frames: list[bytes] = [bytes(first_chunk)]
        started = False
        speech_started_at = time.monotonic()
        last_voice_at = speech_started_at
        silence_limit = self.settings.silence_seconds

        while not self._stop_event.is_set():
            chunk, _overflowed = stream.read(self.settings.block_size)
            chunk_bytes = bytes(chunk)
            frames.append(chunk_bytes)
            samples = np.frombuffer(chunk_bytes, dtype=np.int16)
            rms = float(np.sqrt(np.mean(np.square(samples.astype(np.float32))))) if samples.size else 0.0

            now = time.monotonic()
            if rms >= self.settings.rms_threshold:
                started = True
                last_voice_at = now

            duration = now - speech_started_at
            if started and (now - last_voice_at) >= silence_limit:
                break
            if duration >= self.settings.max_speech_seconds:
                break

        return frames

    def _transcribe(self, frames: list[bytes]) -> str:
        min_frames = int((self.settings.min_speech_seconds * self.settings.sample_rate) / self.settings.block_size)
        if len(frames) < min_frames:
            self._set_state(AssistantState.idle, last_transcript="")
            return ""

        self._set_state(AssistantState.processing)
        recognizer = KaldiRecognizer(self._vosk_model, self.settings.sample_rate)
        for frame in frames:
            recognizer.AcceptWaveform(frame)

        try:
            final = json.loads(recognizer.FinalResult())
        except json.JSONDecodeError:
            self._set_state(AssistantState.error, last_error="Invalid JSON from Vosk recognizer.")
            return ""

        transcript = (final.get("text") or "").strip()
        self._set_state(AssistantState.processing, last_transcript=transcript)
        return transcript

    def _process_text(self, transcript: str) -> dict[str, Any]:
        self._set_state(AssistantState.processing, last_transcript=transcript)

        if not self.settings.groq_api_key:
            error = "GROQ_API_KEY is missing."
            self._set_state(AssistantState.error, last_error=error)
            return {"error": error}

        llm = self._call_llm(transcript)
        spoken_reply = llm.get("spoken_reply", "").strip()
        action = llm.get("action", {"name": "none", "args": {}})
        action_result = self._execute_action(action)
        final_reply = spoken_reply
        if action_result:
            final_reply = f"{spoken_reply} {action_result}".strip()

        self._set_state(AssistantState.speaking, last_reply=final_reply, last_action=action.get("name", "none"))
        self._speak(final_reply)
        self._set_state(AssistantState.idle)

        return {
            "transcript": transcript,
            "spoken_reply": spoken_reply,
            "action": action,
            "action_result": action_result,
            "final_reply": final_reply,
        }

    def _call_llm(self, transcript: str) -> dict[str, Any]:
        endpoint = f"{self.settings.groq_base_url.rstrip('/')}/chat/completions"
        payload = {
            "model": self.settings.groq_model,
            "temperature": 0.3,
            "response_format": {"type": "json_object"},
            "messages": [
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user", "content": transcript},
            ],
        }
        headers = {
            "Authorization": f"Bearer {self.settings.groq_api_key}",
            "Content-Type": "application/json",
        }
        response = self._client.post(endpoint, headers=headers, json=payload)
        response.raise_for_status()
        data = response.json()

        content = (
            data.get("choices", [{}])[0]
            .get("message", {})
            .get("content", "")
        )

        parsed = self._extract_llm_json(content)
        if not isinstance(parsed, dict):
            return {"spoken_reply": "No pude procesar la respuesta del modelo.", "action": {"name": "none", "args": {}}}
        if "spoken_reply" not in parsed:
            parsed["spoken_reply"] = "No tengo respuesta ahora mismo."
        if "action" not in parsed or not isinstance(parsed["action"], dict):
            parsed["action"] = {"name": "none", "args": {}}
        parsed["action"].setdefault("name", "none")
        parsed["action"].setdefault("args", {})
        return parsed

    @staticmethod
    def _extract_llm_json(content: str) -> dict[str, Any]:
        if not content:
            return {}

        content = content.strip()
        try:
            return json.loads(content)
        except json.JSONDecodeError:
            pass

        start = content.find("{")
        end = content.rfind("}")
        if start >= 0 and end > start:
            try:
                return json.loads(content[start:end + 1])
            except json.JSONDecodeError:
                return {}
        return {}

    def _execute_action(self, action: dict[str, Any]) -> str:
        name = str(action.get("name", "none"))
        args = action.get("args", {}) if isinstance(action.get("args"), dict) else {}

        if name == "none":
            return ""
        if name == "get_time":
            return f"Son las {datetime.now().strftime('%H:%M')}."
        if name == "send_message":
            message = str(args.get("message", "")).strip()
            if not message:
                return "No recibi el mensaje para enviar."
            return self._notify_screen(message)
        if name == "spotify_play":
            return self._run_spotify_command(self.settings.spotify_play_command, "He reanudado Spotify.")
        if name == "spotify_pause":
            return self._run_spotify_command(self.settings.spotify_pause_command, "He pausado Spotify.")
        return "No puedo ejecutar esa accion."

    def _notify_screen(self, message: str) -> str:
        headers = {"X-API-KEY": self.settings.notify_api_key}
        try:
            response = self._client.post(self.settings.notify_url, headers=headers, content=message.encode("utf-8"))
            if response.status_code != 200:
                return "No pude enviar el mensaje a la pantalla."
            return "He enviado el mensaje a la pantalla."
        except Exception:
            return "No pude conectar con el servicio de notificaciones."

    def _run_spotify_command(self, command: str, ok_message: str) -> str:
        if not command:
            return "No tengo configurado el control de Spotify."
        try:
            subprocess.run(shlex.split(command), check=True, capture_output=True)
            return ok_message
        except Exception:
            return "No pude ejecutar el control de Spotify."

    def _speak(self, text: str) -> None:
        if not text.strip():
            return

        piper_bin_path = shutil.which(self.settings.piper_bin)
        if piper_bin_path is None:
            self._speak_with_espeak(text)
            return

        if not self.settings.piper_model_path:
            self._set_state(AssistantState.error, last_error="PIPER_MODEL_PATH is missing.")
            return

        model_path = Path(self.settings.piper_model_path)
        if not model_path.exists():
            self._set_state(AssistantState.error, last_error=f"Piper model not found: {model_path}")
            return

        self._set_state(AssistantState.speaking)
        fd, wav_path = tempfile.mkstemp(prefix="nuria-tts-", suffix=".wav")
        os.close(fd)

        try:
            piper_cmd = [piper_bin_path, "--model", str(model_path), "--output_file", wav_path]
            subprocess.run(piper_cmd, input=text.encode("utf-8"), check=True, capture_output=True)

            play_cmd = shlex.split(self.settings.piper_play_command) + [wav_path]
            subprocess.run(play_cmd, check=True, capture_output=True)
        except Exception:
            self._speak_with_espeak(text)
        finally:
            try:
                Path(wav_path).unlink(missing_ok=True)
            except Exception:
                pass

    def _speak_with_espeak(self, text: str) -> None:
        try:
            subprocess.run(
                ["espeak-ng", "-v", "es", "-s", "145", text],
                check=True,
                capture_output=True,
            )
        except Exception as exc:
            self._set_state(AssistantState.error, last_error=f"TTS failed: {exc}")


class LedRingController:
    def __init__(self, settings: Settings):
        self._available = False
        self._pixels = None
        self._settings = settings
        try:
            from rpi_ws281x import PixelStrip, Color  # type: ignore
            self._color = Color
            self._pixels = PixelStrip(
                settings.led_count,
                settings.led_pin,
                800000,
                10,
                False,
                settings.led_brightness,
                0,
            )
            self._pixels.begin()
            self._available = True
        except Exception:
            self._available = False

    def set_state(self, state: AssistantState) -> None:
        if not self._available or self._pixels is None:
            return

        color = self._color(0, 0, 30)  # idle blue
        if state == AssistantState.listening:
            color = self._color(0, 40, 0)  # green
        elif state == AssistantState.processing:
            color = self._color(40, 20, 0)  # amber
        elif state == AssistantState.speaking:
            color = self._color(30, 0, 40)  # purple
        elif state == AssistantState.error:
            color = self._color(60, 0, 0)  # red
        elif state == AssistantState.stopped:
            color = self._color(0, 0, 0)  # off

        for i in range(self._settings.led_count):
            self._pixels.setPixelColor(i, color)
        self._pixels.show()


app = FastAPI(title="Nuria Voice Backend", version="1.0.0")
settings = Settings()
runtime: VoiceAssistantRuntime | None = None
runtime_init_error = ""

try:
    runtime = VoiceAssistantRuntime(settings)
except Exception as exc:
    runtime_init_error = str(exc)


@app.get("/health")
def health() -> dict[str, Any]:
    if runtime is None:
        return {"ok": False, "runtime_running": False, "state": "error", "init_error": runtime_init_error}
    return {
        "ok": True,
        "runtime_running": runtime.snapshot().get("running", False),
        "state": runtime.snapshot().get("state", "stopped"),
    }


@app.get("/assistant/state")
def assistant_state() -> dict[str, Any]:
    if runtime is None:
        raise HTTPException(status_code=503, detail=f"Runtime init failed: {runtime_init_error}")
    return runtime.snapshot()


@app.post("/assistant/start", response_model=StartResponse)
def assistant_start() -> StartResponse:
    if runtime is None:
        raise HTTPException(status_code=503, detail=f"Runtime init failed: {runtime_init_error}")
    started, message = runtime.start()
    return StartResponse(started=started, message=message)


@app.post("/assistant/stop", response_model=StopResponse)
def assistant_stop() -> StopResponse:
    if runtime is None:
        raise HTTPException(status_code=503, detail=f"Runtime init failed: {runtime_init_error}")
    stopped, message = runtime.stop()
    return StopResponse(stopped=stopped, message=message)


@app.post("/assistant/ask")
def assistant_ask(request: AskRequest) -> dict[str, Any]:
    if runtime is None:
        raise HTTPException(status_code=503, detail=f"Runtime init failed: {runtime_init_error}")
    try:
        return runtime.ask_text(request.text.strip())
    except httpx.HTTPStatusError as exc:
        raise HTTPException(status_code=502, detail=f"LLM upstream error: {exc.response.status_code}") from exc
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"Assistant error: {exc}") from exc
