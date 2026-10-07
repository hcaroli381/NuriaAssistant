#!/usr/bin/env bash
# Pushes the already-built assistant to a Raspberry Pi in one command:
#   * the ARM fat jar (its config.properties with the API keys is inside the jar)
#   * deploy/            (these systemd units + installers)
#   * voice-backend/     (main.py, requirements.txt, .env with the Groq key and
#                         the downloaded models)
# With --setup it also creates the Python venv, installs the dependencies and
# runs deploy/install.sh --autologin on the Pi.
#
# Usage:
#   deploy/push-to-pi.sh <user>@<pi-host> [--no-models] [--setup] [--dry-run]
#
#   --no-models  skip voice-backend/models/ (~120 MB); the backend downloads
#                them on the first wake-word start instead (slower)
#   --setup      also run the Pi-side setup: venv + Python deps + install.sh
#                --autologin (10-20 minutes on a Pi 3)
#   --dry-run    show what would be copied without changing anything
#
# The keys never go through git: config.properties and voice-backend/.env are
# gitignored and travel only inside this rsync, over SSH.
set -euo pipefail

TARGET=""
NO_MODELS=0
SETUP=0
DRY_RUN=0

for arg in "$@"; do
    case "$arg" in
        --no-models) NO_MODELS=1 ;;
        --setup)     SETUP=1 ;;
        --dry-run)   DRY_RUN=1 ;;
        -h|--help)
            sed -n '2,20p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
            exit 0 ;;
        -*) echo "Unknown option: $arg" >&2; exit 2 ;;
        *)  TARGET="$arg" ;;
    esac
done

if [ -z "$TARGET" ]; then
    echo "Usage: deploy/push-to-pi.sh <user>@<pi-host> [--no-models] [--setup] [--dry-run]" >&2
    exit 2
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REMOTE_USER="${TARGET%@*}"
REMOTE_DIR="NuriaAssistant"

# --- Find the jar exactly like install.sh does -------------------------------
JAR_PATH=""
for candidate in "$PROJECT_DIR"/target/NuriaAssistant-*-all.jar \
                 "$PROJECT_DIR"/NuriaAssistant-*-all.jar; do
    if [ -f "$candidate" ]; then JAR_PATH="$candidate"; break; fi
done

if [ -z "$JAR_PATH" ]; then
    echo "No prebuilt jar found. Build the ARM one first:" >&2
    echo "  ./mvnw clean package -Ppi -Djavafx.platform=linux-aarch64 -DskipTests" >&2
    exit 1
fi

# --- Refuse to ship an x86 jar: it is the #1 deployment failure --------------
if command -v unzip >/dev/null 2>&1 && command -v file >/dev/null 2>&1; then
    JAR_ARCH="$(unzip -p "$JAR_PATH" libglassgtk3.so 2>/dev/null | file - 2>/dev/null || true)"
    case "$JAR_ARCH" in
        *aarch64*)          echo "Jar architecture: ARM aarch64 (ok)" ;;
        *x86-64*|*x86_64*)  echo "ERROR: $JAR_PATH has x86-64 natives." >&2
                            echo "Rebuild it with -Ppi (without it the Pi fails with" >&2
                            echo "\"JavaFX runtime components are missing\")." >&2
                            exit 1 ;;
        *)                  echo "WARNING: could not confirm the jar architecture; continuing." ;;
    esac
else
    echo "WARNING: unzip/file not found, skipping the architecture check."
fi

# --- Secrets sanity check -----------------------------------------------------
if [ ! -f "$PROJECT_DIR/src/main/resources/config.properties" ]; then
    echo "WARNING: src/main/resources/config.properties is missing, so the jar"
    echo "         carries no API keys (weather/Spotify/Telegram/calendar)."
fi
if [ ! -f "$PROJECT_DIR/voice-backend/.env" ]; then
    echo "WARNING: voice-backend/.env is missing, so the Pi will have no GROQ_API_KEY"
    echo "         (voice answers will fail until you add one there or in ~/.alpha/alpha.env)."
fi

RSYNC_FLAGS=(-av)
[ "$DRY_RUN" -eq 1 ] && RSYNC_FLAGS+=(-n)
VOICE_EXCLUDES=(--exclude .venv --exclude __pycache__)
[ "$NO_MODELS" -eq 1 ] && VOICE_EXCLUDES+=(--exclude models)

echo
echo "Pushing to $TARGET:$REMOTE_DIR/  (dry-run=$DRY_RUN, models=$([ "$NO_MODELS" -eq 1 ] && echo no || echo yes))"
echo

if [ "$DRY_RUN" -eq 0 ]; then
    ssh "$TARGET" "mkdir -p ~/$REMOTE_DIR"
fi

rsync "${RSYNC_FLAGS[@]}" "$JAR_PATH" "$TARGET:$REMOTE_DIR/"
rsync "${RSYNC_FLAGS[@]}" --delete "$PROJECT_DIR/deploy/" "$TARGET:$REMOTE_DIR/deploy/"
rsync "${RSYNC_FLAGS[@]}" --delete "${VOICE_EXCLUDES[@]}" \
      "$PROJECT_DIR/voice-backend/" "$TARGET:$REMOTE_DIR/voice-backend/"

# --- Optional Pi-side setup ---------------------------------------------------
if [ "$SETUP" -eq 1 ] && [ "$DRY_RUN" -eq 0 ]; then
    echo
    echo "Creating the venv and installing the backend dependencies."
    echo "(10-20 minutes on a Pi 3; the SSH session stays attached, do not close it)"
    if ! ssh -tt "$TARGET" "cd ~/$REMOTE_DIR/voice-backend && \
            python3 -m venv .venv && \
            ./.venv/bin/pip install --upgrade pip && \
            ./.venv/bin/pip install -r requirements.txt && \
            ./.venv/bin/pip install piper-tts"; then
        # On ARM, openwakeword's default install can fail on tflite-runtime:
        # this project only uses its ONNX path, so retry with the documented
        # no-deps install, then keep going regardless of the outcome.
        echo
        echo "WARNING: the dependency install failed (usually openwakeword/tflite on ARM)."
        echo "         Retrying with the ONNX-only install..."
        ssh -tt "$TARGET" "cd ~/$REMOTE_DIR/voice-backend && ./.venv/bin/pip install --no-deps \
            openwakeword==0.6.0 onnxruntime scikit-learn scipy" || true
    fi

    echo
    echo "Checking that the backend imports cleanly..."
    if ssh -tt "$TARGET" "cd ~/$REMOTE_DIR/voice-backend && ./.venv/bin/python -c \
            'import fastapi, httpx, numpy, sounddevice, vosk, openwakeword'"; then
        echo "Voice backend dependencies: OK"
    else
        SETUP_WARNED=1
        echo "WARNING: the venv is still incomplete, so nuria-voice.service will fail to start." >&2
        echo "         Fix it on the Pi (PIAGENT.md, apartado 5) and then run:" >&2
        echo "           ssh $TARGET 'cd ~/$REMOTE_DIR/deploy && ./install.sh $REMOTE_USER --autologin'" >&2
    fi

    echo
    echo "Installing the systemd units (kiosk + voice, autologin)..."
    ssh -tt "$TARGET" "cd ~/$REMOTE_DIR/deploy && ./install.sh $REMOTE_USER --autologin"
fi

echo
if [ "${SETUP_WARNED:-0}" -eq 1 ]; then
    echo "Done, but the voice backend needs attention (see the warnings above)."
else
    echo "Done."
fi
echo "Next, on the Pi:"
if [ "$SETUP" -eq 0 ]; then
    echo "  1) python3 -V                                  # check the interpreter"
    echo "  2) cd ~/$REMOTE_DIR/voice-backend && python3 -m venv .venv \\"
    echo "       && ./.venv/bin/pip install -r requirements.txt && ./.venv/bin/pip install piper-tts"
    echo "     (if pip chokes on openwakeword: ./.venv/bin/pip install --no-deps \\"
    echo "        openwakeword==0.6.0 onnxruntime scikit-learn scipy)"
    echo "  3) cd ~/$REMOTE_DIR/deploy && ./install.sh $REMOTE_USER --autologin"
fi
echo "  4) sudo reboot   ->  then check:"
echo "     ssh $TARGET 'journalctl -u nuria-assistant -b | grep \"Alpha UI up\"'"
echo "     ssh $TARGET 'curl -s http://127.0.0.1:8090/health'"
echo
echo "Tip: this jar contains config.properties (your API keys). Keep it private."
