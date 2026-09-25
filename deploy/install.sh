#!/usr/bin/env bash
# Installs (or removes) the Alpha Assistant boot-on-power systemd services, so
# a freshly powered Raspberry Pi comes up as a working assistant with no manual
# step. Two units are managed:
#
#   nuria-voice.service      Python backend (wake word + STT + LLM + TTS), :8090
#   nuria-assistant.service  JavaFX kiosk UI on the desktop session's :0
#
# Usage:
#   ./install.sh [USER] [PROJECT_DIR] [--jar|--source] [--autologin] [--uninstall]
#
#   --jar        run the prebuilt fat jar (default when one is found: it boots
#                in seconds, while a Maven build on a Pi 3 takes minutes)
#   --source     run ./mvnw javafx:run from the project sources
#   --autologin  enable desktop autologin with raspi-config. Required: the UI
#                can only render inside a logged-in desktop session on :0.
#   --uninstall  stop, disable and delete both units again
set -euo pipefail

SERVICE_USER=""
PROJECT_DIR=""
MODE="auto"
AUTOLOGIN=0
UNINSTALL=0

for arg in "$@"; do
    case "$arg" in
        --jar)       MODE="jar" ;;
        --source)    MODE="source" ;;
        --autologin) AUTOLOGIN=1 ;;
        --uninstall) UNINSTALL=1 ;;
        -h|--help)
            sed -n '2,18p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
            exit 0 ;;
        -*) echo "Unknown option: $arg" >&2; exit 2 ;;
        *)
            if [ -z "$SERVICE_USER" ]; then
                SERVICE_USER="$arg"
            elif [ -z "$PROJECT_DIR" ]; then
                PROJECT_DIR="$arg"
            else
                echo "Unexpected argument: $arg" >&2; exit 2
            fi ;;
    esac
done

SERVICE_USER="${SERVICE_USER:-${USER:-$(id -un)}}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEFAULT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
PROJECT_DIR="${PROJECT_DIR:-$DEFAULT_DIR}"
PROJECT_DIR="$(cd "$PROJECT_DIR" && pwd)"
SERVICE_UID="$(id -u "$SERVICE_USER")"
SERVICE_HOME="$(getent passwd "$SERVICE_USER" | cut -d: -f6 || true)"
SERVICE_HOME="${SERVICE_HOME:-/home/$SERVICE_USER}"

# --- Removal ----------------------------------------------------------------
if [ "$UNINSTALL" -eq 1 ]; then
    echo "Removing Alpha services for user '$SERVICE_USER'..."
    sudo systemctl disable --now nuria-assistant.service nuria-voice.service || true
    sudo rm -f /etc/systemd/system/nuria-assistant.service \
               /etc/systemd/system/nuria-voice.service
    sudo systemctl daemon-reload
    sudo systemctl reset-failed || true
    echo "Removed. Nothing starts automatically any more; run it by hand with:"
    echo "  cd $PROJECT_DIR && ./mvnw javafx:run"
    exit 0
fi

echo "Installing Alpha services for user '$SERVICE_USER' at '$PROJECT_DIR'"

# --- Which command should the kiosk unit run? -------------------------------
JAR_PATH=""
for candidate in "$PROJECT_DIR"/target/NuriaAssistant-*-all.jar \
                 "$PROJECT_DIR"/NuriaAssistant-*-all.jar \
                 "$SERVICE_HOME"/NuriaAssistant-*-all.jar; do
    if [ -f "$candidate" ]; then JAR_PATH="$candidate"; break; fi
done

if [ "$MODE" = "auto" ]; then
    MODE="jar"; [ -n "$JAR_PATH" ] || MODE="source"
fi

JAVA_BIN="$(command -v java || true)"
JAVA_BIN="${JAVA_BIN:-/usr/bin/java}"

if [ "$MODE" = "jar" ]; then
    if [ -z "$JAR_PATH" ]; then
        echo "No prebuilt jar found. Build it on your PC first:" >&2
        echo "  ./mvnw clean package -Ppi -Djavafx.platform=linux-aarch64 -DskipTests" >&2
        echo "  scp target/NuriaAssistant-1.0-SNAPSHOT-all.jar $SERVICE_USER@<PI_IP>:~/NuriaAssistant/target/" >&2
        exit 1
    fi
    EXEC_START="$JAVA_BIN -jar $JAR_PATH"
    echo "Kiosk command: java -jar $JAR_PATH  (no build at boot)"
else
    EXEC_START="/bin/bash -lc 'cd $PROJECT_DIR && ./mvnw javafx:run'"
    echo "Kiosk command: ./mvnw javafx:run  (builds at every boot - slower)"
fi

# --- Preflight warnings -----------------------------------------------------
if [ ! -x "$PROJECT_DIR/voice-backend/.venv/bin/python" ]; then
    echo "WARNING: $PROJECT_DIR/voice-backend/.venv/bin/python is missing;"
    echo "         nuria-voice.service will fail. Create it with:"
    echo "         cd $PROJECT_DIR/voice-backend && python3 -m venv .venv && ./.venv/bin/pip install -r requirements.txt"
fi
if [ "$MODE" = "source" ] && ! command -v mvn >/dev/null 2>&1; then
    echo "WARNING: Maven not found; ./mvnw needs a JDK (openjdk-21-jre-headless is enough for the jar)."
fi
# The kiosk's WiFi sheet drives NetworkManager through nmcli; without this
# group polkit denies the change and the sheet can only report the error.
if ! id -nG "$SERVICE_USER" 2>/dev/null | tr ' ' '\n' | grep -qx netdev; then
    echo "WARNING: '$SERVICE_USER' is not in the 'netdev' group, so the on-screen"
    echo "         WiFi setup (Conexión sheet) cannot change networks."
    echo "         Fix with: sudo usermod -aG netdev $SERVICE_USER   (then re-login)"
fi

# --- Render and install the units -------------------------------------------
render() {
    sed -e "s|__USER__|$SERVICE_USER|g" \
        -e "s|__UID__|$SERVICE_UID|g" \
        -e "s|__HOME__|$SERVICE_HOME|g" \
        -e "s|__PROJECT_DIR__|$PROJECT_DIR|g" \
        -e "s|__EXEC_START__|$EXEC_START|g" \
        "$1" | sudo tee "/etc/systemd/system/$(basename "$1")" > /dev/null
}

render "$SCRIPT_DIR/nuria-voice.service"
render "$SCRIPT_DIR/nuria-assistant.service"

sudo systemctl daemon-reload

# Voice backend: starts immediately and on every boot.
sudo systemctl enable --now nuria-voice.service

# Kiosk UI: enable for boot, and start it now without blocking on the
# ExecStartPre wait for the X socket (over SSH there is no display yet).
sudo systemctl enable nuria-assistant.service
sudo systemctl start --no-block nuria-assistant.service || true

# --- Desktop autologin: the UI needs a logged-in session on :0 --------------
autologin_enabled() {
    grep -rqs '^[[:space:]]*autologin-user=[^[:space:]]' \
        /etc/lightdm/lightdm.conf /etc/lightdm/lightdm.conf.d 2>/dev/null
}

if [ "$AUTOLOGIN" -eq 1 ]; then
    if command -v raspi-config >/dev/null 2>&1; then
        sudo raspi-config nonint do_boot_behaviour B4
        echo "Desktop autologin enabled."
    else
        echo "raspi-config not found; enable desktop autologin manually." >&2
    fi
elif [ -f /etc/lightdm/lightdm.conf ] || [ -d /etc/lightdm/lightdm.conf.d ]; then
    if autologin_enabled; then
        echo "Desktop autologin: detected."
    else
        echo
        echo "WARNING: no desktop autologin detected. The units are enabled, but"
        echo "         the UI can only appear once a desktop session is logged in"
        echo "         on :0. Enable it once with either of:"
        echo "           sudo raspi-config nonint do_boot_behaviour B4"
        echo "           cd $SCRIPT_DIR && ./install.sh $SERVICE_USER --autologin"
    fi
fi

echo
echo "Done. Both services are enabled and will start on every power-on."
echo "  status:    systemctl --no-pager status nuria-voice nuria-assistant"
echo "  logs:      journalctl -u nuria-voice -u nuria-assistant -f"
echo "  disable:   cd $SCRIPT_DIR && ./install.sh --uninstall"
