# Alpha Assistant

An always-on smart assistant for the **Raspberry Pi 3** with a 1024×600
touchscreen — a self-hosted Echo Show. It runs 24/7 showing the clock, weather
and calendar, plays Spotify through a Connect device, receives remote messages
from anywhere via Telegram or a local HTTP API, and answers by voice through a
local Python backend (wake word → speech → LLM → TTS).

The whole assistant ships as **one self-contained jar**: when the Python voice
backend is not already running, the app spawns it as a child process on
startup.

## Features

- **Clock & weather** — large high-contrast clock, date, and current conditions
  from OpenWeatherMap.
- **Spotify Connect player** — full-screen player with cover art, track
  metadata and device badge; login by scanning a QR from any phone, with no
  network setup on the Pi (see *Spotify phone pairing*); tokens persist across
  reboots.
- **Remote notifications** — Alpha's speech bubble for messages sent via
  Telegram or `POST /notify` on the local HTTP API.
- **Alarms** — tactile full-screen alarm with snooze/dismiss, repeat days and
  one-shot modes; JSON persistence at `~/.alpha/alarms.json`.
- **iCloud calendar** — read-only public `.ics` feed with a full-screen agenda,
  recurring events and a next-event hint (no Apple credentials on the Pi). The
  share link is sent to the Telegram bot (`/calendario …`), which is much
  easier than typing it on a touchscreen; it is validated, cached and stored so
  it survives reboots.
- **WiFi setup on the touchscreen** — no keyboard on the Pi: a *Conexión* sheet
  shows the device **MAC address** (for routers that whitelist devices), scans
  the networks in range and lets you type the password on Alpha's own
  on-screen keyboard.
- **Voice assistant** — continuous wake word, offline Spanish STT (Vosk),
  LLM-backed replies (Groq), and neural TTS (Piper), all driven from the
  Python backend in `voice-backend/`.
- **Night dimming** — screen-saver dim after idle time during night hours,
  tap to wake.

## Architecture

```
┌───────────────────────────── JavaFX app (java -jar) ─────────────────────────────┐
│  AssistantController (orchestrator / composition root)                           │
│   ├─ ui/NightDimmingController   ├─ ui/NotificationBubbleUi   ├─ ui/WeatherUi    │
│   ├─ services/*  (Alarm, Calendar, Weather, NotificationServer)                  │
│   ├─ services/TelegramService        └─ services/VoiceAssistantService ──┐       │
│   └─ spotify/* (SpotifyService, token store, QR generator, phone pairing) │       │
└──────────────────────────────────────────────────────────────────────────│───────┘
                                                                           ▼
┌──────────────────────────── Python voice backend ───────────────────────────────┐
│  voice-backend/main.py (FastAPI on :8090)                                       │
│  wake word (openWakeWord) → STT (Vosk) → LLM (Groq) → TTS (Piper)               │
│  tool actions: get_time, send_message (→ :8080/notify), spotify_play/pause      │
└──────────────────────────────────────────────────────────────────────────────────┘
```

The Java app is the orchestrator: a single daemon ticker drives all periodic
work (voice 1s, Spotify 4s, calendar 15 min, weather 30 min) off the JavaFX
animation clock, and the UI is change-deduplicated so idle time costs almost
nothing on the Pi.

## Repository layout

| Path | Contents |
| --- | --- |
| `src/main/java/.../AssistantController.java` | Main JavaFX controller (composition root) |
| `src/main/java/.../services/` | Backend services (HTTP clients, servers, persistence, `WifiService` for `nmcli`, `RuntimeSettings` for runtime overrides) |
| `src/main/java/.../spotify/` | Spotify OAuth2, token store, QR generation |
| `src/main/java/.../ui/` | Extracted UI controllers (night dimming, speech bubble, weather mapping), the on-screen keyboard + Alpha's bundled typeface |
| `src/main/resources/` | FXML layout, stylesheet, fonts, sounds, config templates |
| `docs/` | Static GitHub Pages file used as the Spotify HTTPS redirect (`spotify-callback.html`) |
| `voice-backend/` | Python voice assistant (FastAPI + wake word/STT/LLM/TTS) |
| `deploy/` | systemd units + installer for boot-on-power deployment |

## Quick start (development on a laptop)

Requirements: **JDK 21+** and Maven (the `mvnw` wrapper is included).

```bash
# 1. Configure credentials (copy the template, fill in your keys)
cp src/main/resources/config.properties.example src/main/resources/config.properties

# 2. Run the app
./mvnw javafx:run
```

Optional — voice backend locally:

```bash
cd voice-backend
python3 -m venv .venv
./.venv/bin/pip install -r requirements.txt
cp .env.example .env        # fill GROQ_API_KEY; model paths are relative to voice-backend/
./.venv/bin/python -m uvicorn main:app --host 0.0.0.0 --port 8090
```

The Java app auto-starts the backend from `voice-backend/` when it is
unreachable, so one `java -jar` run contains the whole system.

## Configuration

Java-side keys go in `config.properties` (or as environment variables, which
take priority):

| Key | Purpose |
| --- | --- |
| `OPENWEATHER_API_KEY` / `OPENWEATHER_CITY` | Weather service (e.g. `Granada,ES`) |
| `SPOTIFY_CLIENT_ID` / `SPOTIFY_CLIENT_SECRET` | Spotify OAuth2 credentials |
| `SPOTIFY_RELAY_URL` | HTTPS bounce page that hands the login back to the Pi (see *Spotify phone pairing*) |
| `SPOTIFY_REDIRECT_URI` | Loopback fallback for desktop development (default `http://127.0.0.1:8888/callback`) |
| `OWNER_NAME` | Name Alpha greets on the alarm screen (`Buenos días, Nuria`) |
| `TELEGRAM_BOT_TOKEN` / `TELEGRAM_ALLOWED_CHAT_ID` | Remote messaging; optional chat-ID allowlist |
| `CALENDAR_ICS_URL` | Public read-only iCloud `.ics` share link (empty = feature off; can also be set at runtime with `/calendario` on Telegram) |
| `DIM_IDLE_MINUTES` / `DIM_START_HOUR` / `DIM_END_HOUR` | Night-dimming screen saver (default 10 / 22 / 8) |
| `VOICE_BACKEND_URL` / `VOICE_BACKEND_DIR` / `VOICE_PYTHON_BIN` | Voice backend discovery / spawning |

Voice-backend keys live in `voice-backend/.env` (see `.env.example`): audio
thresholds, `GROQ_API_KEY` + model, Piper TTS paths (relative paths are
anchored to `voice-backend/`), tool-action commands and the notification API
key.

### Connecting the calendar (from your phone)

1. On the iPhone: **Calendario → el calendario → Compartir calendario →
   activa "Calendario público" → Copiar enlace**.
2. Send it to Alpha's Telegram bot: `/calendario webcal://…`
3. She validates it, stores it (so it survives reboots) and answers with how
   many events she read. `/help` lists the commands.

The link can also be put in `CALENDAR_ICS_URL` before deployment; a value sent
to the bot takes priority over the one in the config file.

> Share a dedicated calendar, never your main one: anyone holding the link can
> read it.

### Changing the WiFi network

Alpha boots with no keyboard, so the network is set on screen: tap the green
signal icon in the top-right row. The sheet shows the device **MAC address**
(add it to your router's whitelist if it filters by MAC) and the current
connection; *Buscar redes* lists the networks in range and the password is
typed on Alpha's own on-screen keyboard.

The kiosk user must be in the `netdev` group so NetworkManager lets the app
change connections (`sudo usermod -aG netdev $USER`, then log out and back in —
`deploy/install.sh` warns when it is missing).

## Spotify phone pairing

Spotify requires **HTTPS** redirect URIs (a plain-`http` LAN address is
rejected since April 2025), but the Pi has no certificate and no public
address. The login therefore goes through one static page:

```
phone ── scan QR ──► accounts.spotify.com ──► https://<you>.github.io/NuriaAssistant/spotify-callback.html
                                                        │  (reads the Pi's LAN address out of OAuth `state`)
                                                        ▼
                                              http://<PI_LAN_IP>:8888/callback
```

Nothing is stored server-side: the state carries the Pi's address (plus its
mDNS name as a fallback, in case its DHCP lease moved), and the page bounces
the phone straight to the Pi over your own Wi-Fi.

One-time setup:

1. Push this repo and enable **Settings → Pages → Deploy from branch → `main` / `/docs`**.
   The site root (`https://<you>.github.io/NuriaAssistant/`) renders a short
   landing page restating these steps.
2. Add the resulting callback URL
   (`https://<you>.github.io/NuriaAssistant/spotify-callback.html`)
   to **Redirect URIs** in the Spotify Developer Dashboard.
3. Put the same URL in `SPOTIFY_RELAY_URL`.

After that, `Vincular Spotify` shows a QR: she scans it, logs in, and the
screen connects itself. Tokens persist at `~/.alpha/spotify-tokens.json` and
are refreshed silently on boot.

If the phone never comes back with the confirmation, isolate the hop:

- `http://<PI_LAN_IP>:8888/ping` from the same phone → if it answers, the Wi-Fi
  is fine and the redirect URI in the Dashboard is the problem (Spotify then
  shows `INVALID_CLIENT: Invalid redirect URI` on its own page); if it times
  out, the phone is on a different network.
- The app console logs every incoming callback (`OAuth callback from …`), so an
  empty log means Spotify never redirected the phone.

## Remote messaging

```bash
curl -X POST http://<PI_IP>:8080/notify \
     -H "X-API-KEY: nuria-assistant-secret-key" \
     -d "Hola! No te olvides de comprar pan 🥖"
```

Or talk to the Telegram bot: any message it receives appears in Alpha's speech
bubble on screen (`/clear` removes it again).

## Raspberry Pi deployment

Build on a PC, copy to the Pi:

```bash
./mvnw clean package -Ppi -Djavafx.platform=linux-aarch64 -DskipTests
scp target/NuriaAssistant-1.0-SNAPSHOT-all.jar pi@<PI_IP>:~/
ssh pi@<PI_IP> 'JDK_JAVA_OPTIONS="-Xms64m -Xmx384m -XX:+UseSerialGC -XX:TieredStopAtLevel=1" java -jar NuriaAssistant-1.0-SNAPSHOT-all.jar'
```

The recommended Pi 3 JVM flags (capped heap, serial GC, C1-only JIT) are also
wired into `deploy/nuria-assistant.service` via `JDK_JAVA_OPTIONS`.

### Boot-on-power (the Pi *is* the assistant)

The goal: give the Pi power and it is already the assistant — never launch it
by hand again. `nuria-voice.service` starts the Python backend and
`nuria-assistant.service` launches the kiosk on the desktop session's `:0`,
both on every boot.

**Do this once, after any change you want the Pi to pick up at boot:**

- [ ] **1. Build the ARM jar on your PC** (never `-Ppi` for local runs):
  ```bash
  ./mvnw clean package -Ppi -Djavafx.platform=linux-aarch64 -DskipTests
  ```
- [ ] **2. Copy it to the Pi, inside the project** (the installer looks in
      `target/`, next to the project and in `~`):
  ```bash
  scp target/NuriaAssistant-1.0-SNAPSHOT-all.jar pi@<PI_IP>:~/NuriaAssistant/target/
  ```
- [ ] **3. Install the units on the Pi** — and let the installer turn on
      desktop autologin, because the UI cannot appear without a logged-in
      desktop session on `:0`:
  ```bash
  ssh pi@<PI_IP> 'cd ~/NuriaAssistant/deploy && ./install.sh $USER --autologin'
  ```
  Read its output: it warns if the jar is missing, if
  `voice-backend/.venv/bin/python` does not exist, or if autologin is still off.
- [ ] **4. Reboot and walk away.**
  ```bash
  ssh pi@<PI_IP> 'sudo reboot'
  ```
  No login, no terminal, no command: the clock should appear on its own. If it
  does not, the display wait has not seen `:0` yet — the unit keeps retrying
  every 10s, and `journalctl -u nuria-assistant -f` says why.

**Check / configure it later (over SSH, from outside):**

```bash
systemctl --no-pager status nuria-voice nuria-assistant   # are they up?
journalctl -u nuria-voice -u nuria-assistant -f           # live logs

# Stop autostart to edit config, then launch by hand:
sudo systemctl stop nuria-assistant nuria-voice
sudo systemctl disable nuria-assistant nuria-voice
DISPLAY=:0 JDK_JAVA_OPTIONS='-Xms64m -Xmx384m -XX:+UseSerialGC -XX:TieredStopAtLevel=1' \
  java -jar ~/NuriaAssistant/target/NuriaAssistant-1.0-SNAPSHOT-all.jar

# Undo the whole thing (stop + disable + delete both units):
cd ~/NuriaAssistant/deploy && ./install.sh --uninstall
# ...and put it back at any time: ./install.sh $USER --autologin
```

> Running the jar by hand also spawns the voice backend itself, so leave
> `nuria-voice` stopped or the two will fight over port 8090.

> Never use `-Ppi` for local dev — it bundles ARM natives that won't load on
> x86.

## Testing

```bash
./mvnw test
```

CI (GitHub Actions) runs the Java test suite and a Python compile check of the
voice backend on every push/PR.

## Performance notes

The project is tuned for a Raspberry Pi 3 (1 GB RAM, 4× Cortex-A53): periodic
work runs off the FX animation clock, scene-graph writes are
change-deduplicated (label layout is expensive), animated overlays are cached,
and the wake-word inference is energy-gated so idle CPU stays near zero. The
full rules live in `CLAUDE.md` — do not regress them.

**Testing without the hardware:** `deploy/pi-sim.sh` approximates the Pi's
behaviour on a dev machine by pinning the JVM to two logical CPUs, capping the
heap to the deployed `-Xmx384m` profile, forcing the software rendering
pipeline and using the C1-only + serial-GC flags — a faithful *feel*
simulation (QEMU ARM emulation is slower than the Pi, so it only proves the
code runs on the architecture).
