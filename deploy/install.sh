#!/usr/bin/env bash
# Installs the Alpha Assistant systemd services.
# Usage: ./install.sh [USER] [PROJECT_DIR]
set -euo pipefail

SERVICE_USER="${1:-$USER}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEFAULT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
PROJECT_DIR="${2:-$DEFAULT_DIR}"

echo "Installing Alpha services for user '$SERVICE_USER' at '$PROJECT_DIR'"

sed -e "s|__USER__|${SERVICE_USER}|g" \
    -e "s|__PROJECT_DIR__|${PROJECT_DIR}|g" \
    "$SCRIPT_DIR/nuria-voice.service" | sudo tee /etc/systemd/system/nuria-voice.service > /dev/null

sed -e "s|__USER__|${SERVICE_USER}|g" \
    -e "s|__PROJECT_DIR__|${PROJECT_DIR}|g" \
    "$SCRIPT_DIR/nuria-assistant.service" | sudo tee /etc/systemd/system/nuria-assistant.service > /dev/null

sudo systemctl daemon-reload
sudo systemctl enable --now nuria-voice.service
sudo systemctl enable nuria-assistant.service

echo "Done. Check status with:"
echo "  systemctl --no-pager status nuria-voice nuria-assistant"
