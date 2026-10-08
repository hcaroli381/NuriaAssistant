from __future__ import annotations

import json
import math
import os
import queue
import shlex
import shutil
import subprocess
import tempfile
import threading
import time
import wave
from dataclasses import dataclass, asdict
from datetime import datetime
from enum import Enum
from pathlib import Path
from typing import Any

import httpx
import numpy as np
import sounddevice as sd
from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException
from openwakeword.model import Model as OpenWakeWordModel
from openwakeword.utils import download_models as openwakeword_download_models
from pydantic import BaseModel, Field
from vosk import KaldiRecognizer, Model as VoskModel

# Load .env from the backend directory regardless of the launch CWD.
load_dotenv(Path(__file__).resolve().parent / ".env")


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
    # Capture rate of the microphone. Defaults to 48000 Hz (native hardware rate for
    # USB mics like TI PCM2902/C-Media). 0 = auto fallback.
    capture_sample_rate: int = _env_int("VOICE_CAPTURE_SAMPLE_RATE", 48000)
    wake_threshold: float = _env_float("VOICE_WAKE_THRESHOLD", 0.45)
    rms_threshold: float = _env_float("VOICE_RMS_THRESHOLD", 450.0)
    silence_seconds: float = _env_float("VOICE_SILENCE_SECONDS", 1.2)
    min_speech_seconds: float = _env_float("VOICE_MIN_SPEECH_SECONDS", 0.7)
    max_speech_seconds: float = _env_float("VOICE_MAX_SPEECH_SECONDS", 8.0)
    pre_speech_timeout_seconds: float = _env_float("VOICE_PRE_SPEECH_TIMEOUT_SECONDS", 2.5)
    post_reply_flush_seconds: float = _env_float("VOICE_POST_REPLY_FLUSH_SECONDS", 0.8)
    wake_cooldown_seconds: float = _env_float("VOICE_WAKE_COOLDOWN_SECONDS", 2.5)
    # Energy gate: skip the (expensive) ONNX wake-word inference on near-silent
    # chunks. A silent chunk can never produce a wake match. Normal room noise is
    # ~270-350 RMS, while voice is >450 RMS.
    wake_min_rms: float = _env_float("VOICE_WAKE_MIN_RMS", 380.0)
    # Extra quiet time after Alpha finishes replying: the wake word stays
    # ignored so her own voice echo and surrounding chatter cannot re-trigger
    # her right after an answer.
    post_reply_quiet_seconds: float = _env_float("VOICE_POST_REPLY_QUIET_SECONDS", 4.0)

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

    def __post_init__(self) -> None:
        # Anchor relative model paths to this file's directory so the same
        # .env works no matter where uvicorn is launched from.
        base = Path(__file__).resolve().parent
        for name in ("wakeword_model_path", "vosk_model_path", "piper_model_path"):
            value = getattr(self, name)
            if value:
                path = Path(value).expanduser()
                if not path.is_absolute():
                    setattr(self, name, str(base / path))
        # Same treatment for the piper binary: a path like ./.venv/bin/piper
        # must resolve relative to this directory, while a bare command name
        # (e.g. "piper") is left untouched for PATH lookup.
        piper_bin = self.piper_bin
        if piper_bin and any(sep in piper_bin for sep in ("/", "\\")):
            path = Path(piper_bin).expanduser()
            if not path.is_absolute():
                self.piper_bin = str(base / path)


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
    rms: float = 0.0


class AskRequest(BaseModel):
    text: str = Field(min_length=1, max_length=3000)


class StartResponse(BaseModel):
    started: bool
    message: str


class StopResponse(BaseModel):
    stopped: bool
    message: str


SYSTEM_PROMPT = """You are Alpha, a concise Spanish home assistant.
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


class _Resampler:
    """Band-limited converter from the microphone's native rate to 16 kHz.

    Most USB microphones capture at 48 kHz (or 44.1 kHz) only, while openWakeWord
    and Vosk are trained on 16 kHz audio - and PortAudio refuses to open such a
    device at 16 kHz (paInvalidSampleRate), which leaves the runtime unable to
    hear anything at all. This converts the captured blocks instead, with a
    windowed-sinc (Hamming) polyphase bank: for an integer ratio it degenerates
    into an exact anti-aliased decimation, which is the 48000 -> 16000 path this
    Pi takes. State is kept between blocks, so the stream stays continuous and
    no click appears at the block boundaries.
    """

    def __init__(self, src_rate: int, dst_rate: int, taps: int = 25) -> None:
        if src_rate <= 0 or dst_rate <= 0:
            raise ValueError("sample rates must be positive")
        if src_rate == dst_rate:
            raise ValueError("source and destination sample rates are equal")
        if taps < 3 or taps % 2 == 0:
            raise ValueError("taps must be an odd number >= 3")

        divisor = math.gcd(int(src_rate), int(dst_rate))
        self._up = int(dst_rate) // divisor
        self._down = int(src_rate) // divisor
        # Keep the kernel wide enough to stay a real low-pass when the ratio is
        # not a simple integer (e.g. 44100 -> 16000). The 48000 -> 16000 path
        # this Pi takes keeps the short one, so the hot loop stays cheap.
        taps = max(taps, 4 * self._up)
        if taps % 2 == 0:
            taps += 1
        self._half = taps // 2
        self._offsets = np.arange(-self._half, self._half + 1, dtype=np.int64)

        # Low-pass at the lower of the two Nyquist limits, in units of the input
        # sample rate, so nothing above the target band folds back. For
        # 48000 -> 16000 this is sinc(j/3): an exact decimation kernel.
        cutoff = 0.5 * min(1.0, float(dst_rate) / float(src_rate))
        phase = np.arange(self._up, dtype=np.float64) / self._up
        distance = self._offsets.astype(np.float64)[None, :] - phase[:, None]
        window = np.where(
            np.abs(distance) <= self._half,
            0.54 + 0.46 * np.cos(np.pi * distance / self._half),
            0.0,
        )
        bank = np.sinc(2.0 * cutoff * distance) * window
        bank /= np.sum(bank, axis=1, keepdims=True)
        self._bank = bank.astype(np.float32)

        self._buffer = np.zeros(0, dtype=np.float32)
        self._base = 0  # global input index of self._buffer[0]
        # Start half a window in, so the first output sample has history on both
        # sides: no startup transient, at the cost of ~0.8 ms of latency.
        self._cursor = self._half * self._up  # instant of the next output, in 1/up samples

    def push(self, block: np.ndarray) -> np.ndarray:
        """Resample one captured block, returning the samples that are ready."""
        samples = np.asarray(block, dtype=np.float32).reshape(-1)
        if samples.size:
            self._buffer = np.concatenate((self._buffer, samples))
        last = self._base + self._buffer.size - 1

        # Output k sits at input instant (cursor + k*down)/up and needs the
        # window [instant - half, instant + half] to be inside the buffer.
        ready = int((self._up * (last - self._half) - self._cursor) // self._down) + 1
        if ready <= 0:
            return np.zeros(0, dtype=np.int16)

        instants = self._cursor + self._down * np.arange(ready, dtype=np.int64)
        starts = instants // self._up
        phases = instants % self._up
        indices = (starts[:, None] + self._offsets[None, :]) - self._base
        values = np.sum(self._buffer[indices] * self._bank[phases], axis=1)

        self._cursor += self._down * ready
        keep_from = int(self._cursor // self._up) - self._half
        if keep_from > self._base:
            self._buffer = self._buffer[keep_from - self._base:]
            self._base = keep_from

        return np.clip(np.rint(values), -32768, 32767).astype(np.int16)


class VoiceAssistantRuntime:
    def __init__(self, settings: Settings):
        self.settings = settings
        self.state = RuntimeState()
        self._state_lock = threading.Lock()
        self._thread: threading.Thread | None = None
        self._stop_event = threading.Event()
        self._resampler: _Resampler | None = None
        self._capture_block = settings.block_size
        self._pending = np.zeros(0, dtype=np.int16)
        self._audio_queue: queue.Queue[bytes] = queue.Queue(maxsize=150)
        self._overflow_counter = 0
        self._decim_kernel = np.array([1, 2, 3, 2, 1], dtype=np.float32) / 9.0
        self._capture_rate = settings.capture_sample_rate or 48000
        self._model = self._load_wakeword_model()
        self._vosk_model = self._load_vosk_model()
        self._piper_voice = self._load_piper_voice()
        self._client = httpx.Client(timeout=self.settings.groq_timeout_seconds)
        self._led = LedRingController(settings) if settings.led_enabled else None

    def _load_wakeword_model(self) -> OpenWakeWordModel:
        # Prefer ONNX to avoid hard dependency on tflite-runtime on some platforms.
        if self.settings.wakeword_model_path:
            model_path = Path(self.settings.wakeword_model_path)
            if not model_path.exists():
                raise FileNotFoundError(f"Wake word model path not found: {model_path}")
            self._ensure_openwakeword_resources()
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

    @staticmethod
    def _ensure_openwakeword_resources() -> None:
        # openwakeword's wheel ships no ONNX feature-extraction models; they are
        # fetched from GitHub on first use. Without them, even a locally provided
        # wake word model fails to load (missing melspectrogram.onnx).
        import openwakeword

        resources_dir = Path(openwakeword.__file__).resolve().parent / "resources" / "models"
        if not (resources_dir / "melspectrogram.onnx").exists():
            # Empty model list: fetches feature-extraction + VAD models only.
            openwakeword_download_models(model_names=[])

    def _load_vosk_model(self) -> VoskModel:
        if not self.settings.vosk_model_path:
            raise ValueError("VOSK_MODEL_PATH is required.")
        model_path = Path(self.settings.vosk_model_path)
        if not model_path.exists():
            raise FileNotFoundError(f"VOSK model path not found: {model_path}")
        return VoskModel(str(model_path))

    def _load_piper_voice(self) -> Any:
        if not self.settings.piper_model_path:
            return None
        model_path = Path(self.settings.piper_model_path)
        if not model_path.exists():
            print(f"Voice backend: Piper model not found at {model_path}")
            return None
        try:
            import piper
            voice = piper.PiperVoice.load(str(model_path))
            print(f"Voice backend: preloaded Piper model ({model_path.name}) into memory.")
            return voice
        except Exception as exc:
            print(f"Voice backend: could not preload PiperVoice in memory ({exc}), will use CLI fallback.")
            return None

    def _flush_audio(self) -> None:
        """Discard queued mic blocks, clear decimation buffers, and reset wake word features."""
        while not self._audio_queue.empty():
            try:
                self._audio_queue.get_nowait()
            except queue.Empty:
                break
        self._pending = np.zeros(0, dtype=np.int16)
        self._overflow_counter = 0
        if hasattr(self._model, "reset"):
            try:
                self._model.reset()
            except Exception:
                pass

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

    @staticmethod
    def _find_input_device() -> int | None:
        try:
            devices = sd.query_devices()
            # 1. Prioritize USB capture devices (TI PCM2902, USB PnP, etc.)
            for idx, dev in enumerate(devices):
                if dev.get("max_input_channels", 0) > 0:
                    name = dev.get("name", "").lower()
                    if any(term in name for term in ("usb", "pnp", "pcm", "audio")):
                        return idx
            # 2. Fallback to any device with input channels
            for idx, dev in enumerate(devices):
                if dev.get("max_input_channels", 0) > 0:
                    return idx
            # 3. PortAudio default device
            default_in = sd.default.device[0]
            return default_in if default_in >= 0 else None
        except Exception:
            return None

    def _audio_callback(self, indata, frames, time_info, status) -> None:
        if status.input_overflow:
            self._overflow_counter += 1
        try:
            self._audio_queue.put_nowait(bytes(indata))
        except queue.Full:
            try:
                self._audio_queue.get_nowait()
            except queue.Empty:
                pass
            try:
                self._audio_queue.put_nowait(bytes(indata))
            except queue.Full:
                pass

    def _open_input_stream(self) -> sd.RawInputStream:
        """Open the microphone for capture with auto-retry and callback delivery."""
        dev_idx = self._find_input_device()
        rate = self.settings.capture_sample_rate or 48000
        # blocksize is scaled proportional to the capture rate (80 ms)
        block = int(round(self.settings.block_size * rate / self.settings.sample_rate))
        self._capture_rate = rate
        self._capture_block = block
        self._overflow_counter = 0

        # Drain queue leftovers
        while not self._audio_queue.empty():
            try:
                self._audio_queue.get_nowait()
            except queue.Empty:
                break

        last_exc: Exception | None = None
        for attempt in range(5):
            if self._stop_event.is_set():
                raise RuntimeError("Runtime stopped during stream initialization")
            try:
                kwargs: dict[str, Any] = {
                    "samplerate": rate,
                    "blocksize": block,
                    "channels": self.settings.channels,
                    "dtype": "int16",
                    "latency": 0.2,
                    "callback": self._audio_callback,
                }
                if dev_idx is not None:
                    kwargs["device"] = dev_idx
                stream = sd.RawInputStream(**kwargs)
                print(f"Voice backend: capturing at {rate} Hz (blocksize={block}, device={dev_idx}, latency=0.2).")
                return stream
            except Exception as exc:
                last_exc = exc
                print(f"Voice backend: audio open attempt {attempt + 1}/5 failed ({exc}). Retrying in 1s...")
                time.sleep(1.0)
                dev_idx = self._find_input_device()

        # Fallback to direct default stream if 48k device failed
        if rate != self.settings.sample_rate:
            try:
                stream = sd.RawInputStream(
                    samplerate=self.settings.sample_rate,
                    blocksize=self.settings.block_size,
                    channels=self.settings.channels,
                    dtype="int16",
                    latency=0.2,
                    callback=self._audio_callback,
                )
                self._capture_rate = self.settings.sample_rate
                self._capture_block = self.settings.block_size
                print(f"Voice backend: fell back to direct {self.settings.sample_rate} Hz stream.")
                return stream
            except Exception:
                pass

        if last_exc:
            raise last_exc
        raise RuntimeError("Failed to open audio input stream")

    def _read_chunk(self) -> tuple[bytes, bool]:
        """Read exactly one 16 kHz block (1280 samples) converted from capture stream."""
        overflowed = self._overflow_counter > 0
        self._overflow_counter = 0

        data = None
        while not self._stop_event.is_set():
            try:
                data = self._audio_queue.get(timeout=0.1)
                break
            except queue.Empty:
                continue

        if data is None or not data:
            return b"", False

        raw = np.frombuffer(data, dtype=np.int16)
        if self._capture_rate == 48000:
            # High-performance 3:1 anti-aliasing decimation (48k -> 16k in ~0.16 ms)
            f = raw.astype(np.float32)
            filtered = np.convolve(f, self._decim_kernel, mode="same")[::3]
            chunk = np.clip(np.rint(filtered), -32768, 32767).astype(np.int16)
            return chunk.tobytes(), overflowed
        elif self._capture_rate == self.settings.sample_rate:
            return bytes(data), overflowed
        else:
            # Fallback resampler for arbitrary sample rates
            if self._resampler is None:
                self._resampler = _Resampler(self._capture_rate, self.settings.sample_rate)
            converted = self._resampler.push(raw)
            if converted.size:
                self._pending = np.concatenate((self._pending, converted))
            if self._pending.size >= self.settings.block_size:
                chunk = self._pending[: self.settings.block_size]
                self._pending = self._pending[self.settings.block_size:]
                return chunk.tobytes(), overflowed
            return b"", overflowed

    def _run_loop(self) -> None:
        cooldown_until = 0.0
        prev_wake_above = False
        try:
            with self._open_input_stream():
                while not self._stop_event.is_set():
                    chunk, overflowed = self._read_chunk()
                    if not chunk:
                        continue
                    if overflowed:
                        self._set_state(AssistantState.idle, last_error="Audio overflow on input stream.")
                    audio_np = np.frombuffer(chunk, dtype=np.int16)

                    rms = (float(np.sqrt(np.mean(np.square(audio_np.astype(np.float32)))))
                           if audio_np.size else 0.0)
                    if rms >= self.settings.wake_min_rms:
                        score_name, score_value = self._wakeword_score(audio_np)
                        self._set_state(
                            AssistantState.idle,
                            wake_word_score=score_value,
                            wake_word_model=score_name,
                            rms=round(rms, 1),
                        )
                    else:
                        score_value = 0.0
                        if int(now * 2) != int((now - 0.08) * 2):
                            self._set_state(
                                AssistantState.idle,
                                wake_word_score=0.0,
                                rms=round(rms, 1),
                            )

                    now = time.monotonic()
                    above = score_value >= self.settings.wake_threshold
                    moderate = score_value >= (self.settings.wake_threshold * 0.75)
                    can_wake = (above or (moderate and prev_wake_above)) and now >= cooldown_until

                    if can_wake:
                        cooldown_until = now + self.settings.wake_cooldown_seconds
                        prev_wake_above = False
                        frames = self._capture_utterance(bytes(chunk))
                        transcript = self._transcribe(frames)
                        if transcript:
                            try:
                                self._process_text(transcript)
                                self._flush_audio()
                                self._drain_audio(self.settings.post_reply_flush_seconds)
                                cooldown_until = max(
                                    cooldown_until,
                                    time.monotonic() + self.settings.post_reply_quiet_seconds,
                                )
                            except Exception as exc:
                                self._set_state(AssistantState.error, last_error=f"Processing error: {exc}")
                        else:
                            self._set_state(AssistantState.idle, last_transcript="")
                            self._flush_audio()
                    else:
                        prev_wake_above = moderate
        except Exception as exc:
            self._set_state(AssistantState.error, running=False, last_error=str(exc))

    def _drain_audio(self, seconds: float) -> None:
        deadline = time.monotonic() + seconds
        while not self._stop_event.is_set() and time.monotonic() < deadline:
            try:
                self._audio_queue.get(timeout=0.05)
            except queue.Empty:
                pass

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

    def _capture_utterance(self, first_chunk: bytes) -> list[bytes]:
        self._set_state(AssistantState.listening)
        frames: list[bytes] = [bytes(first_chunk)]
        started = False
        speech_started_at = time.monotonic()
        last_voice_at = speech_started_at
        silence_limit = self.settings.silence_seconds

        while not self._stop_event.is_set():
            chunk, _overflowed = self._read_chunk()
            chunk_bytes = bytes(chunk)
            frames.append(chunk_bytes)
            samples = np.frombuffer(chunk_bytes, dtype=np.int16)
            rms = float(np.sqrt(np.mean(np.square(samples.astype(np.float32))))) if samples.size else 0.0

            now = time.monotonic()
            if rms >= self.settings.rms_threshold:
                started = True
                last_voice_at = now

            duration = now - speech_started_at
            if not started and duration >= self.settings.pre_speech_timeout_seconds:
                # Wake word fired but nobody spoke: return to idle quickly.
                break
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

    def _play_wav(self, wav_path: str) -> bool:
        play_candidates: list[list[str]] = []
        if self.settings.piper_play_command:
            play_candidates.append(shlex.split(self.settings.piper_play_command) + [wav_path])
        play_candidates.append(["pw-play", wav_path])
        play_candidates.append(["aplay", "-q", wav_path])

        for cmd in play_candidates:
            if shutil.which(cmd[0]):
                try:
                    res = subprocess.run(cmd, capture_output=True, timeout=30)
                    if res.returncode == 0:
                        return True
                except Exception:
                    continue
        return False

    def _speak(self, text: str) -> None:
        if not text.strip():
            return

        self._set_state(AssistantState.speaking)
        fd, wav_path = tempfile.mkstemp(prefix="alpha-tts-", suffix=".wav")
        os.close(fd)

        try:
            synth_ok = False
            # 1. High-performance in-memory Piper synthesis (preloaded model)
            if self._piper_voice is not None:
                try:
                    with wave.open(wav_path, "wb") as wav_file:
                        self._piper_voice.synthesize_wav(text, wav_file)
                    synth_ok = True
                except Exception as exc:
                    print(f"Voice backend: in-memory Piper synthesis error ({exc}), trying CLI")

            # 2. Fallback to CLI piper invocation
            if not synth_ok:
                piper_bin_path = None
                if self.settings.piper_bin:
                    candidate = Path(self.settings.piper_bin)
                    if candidate.is_file():
                        piper_bin_path = str(candidate)
                    else:
                        piper_bin_path = shutil.which(self.settings.piper_bin)

                if piper_bin_path and self.settings.piper_model_path:
                    model_path = Path(self.settings.piper_model_path)
                    if model_path.exists():
                        piper_cmd = [piper_bin_path, "--model", str(model_path), "--output_file", wav_path]
                        res = subprocess.run(piper_cmd, input=text.encode("utf-8"), capture_output=True)
                        if res.returncode == 0:
                            synth_ok = True

            # 3. Audio playback (PipeWire / ALSA)
            played = False
            if synth_ok and Path(wav_path).exists() and Path(wav_path).stat().st_size > 44:
                played = self._play_wav(wav_path)

            if not played:
                self._speak_with_espeak(text)
        except Exception as exc:
            print(f"Voice backend: TTS error ({exc}), falling back to espeak")
            self._speak_with_espeak(text)
        finally:
            try:
                Path(wav_path).unlink(missing_ok=True)
            except Exception:
                pass

    def _speak_with_espeak(self, text: str) -> None:
        fd, wav_path = tempfile.mkstemp(prefix="alpha-espeak-", suffix=".wav")
        os.close(fd)
        try:
            res = subprocess.run(
                ["espeak-ng", "-v", "es", "-s", "145", "-w", wav_path, text],
                capture_output=True,
            )
            if res.returncode == 0 and Path(wav_path).exists() and Path(wav_path).stat().st_size > 44:
                if self._play_wav(wav_path):
                    return

            subprocess.run(
                ["espeak-ng", "-v", "es", "-s", "145", text],
                check=True,
                capture_output=True,
            )
        except Exception as exc:
            self._set_state(AssistantState.error, last_error=f"TTS failed: {exc}")
        finally:
            try:
                Path(wav_path).unlink(missing_ok=True)
            except Exception:
                pass


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


app = FastAPI(title="Alpha Voice Backend", version="1.0.0")
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
