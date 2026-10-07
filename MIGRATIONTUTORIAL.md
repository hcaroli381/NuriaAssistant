# MIGRATIONTUTORIAL.md — Pasar Alpha a la Raspberry Pi

Tutorial **para hacer a mano**, en orden, desde el PC. Cada paso dice qué
escribir y qué tienes que ver antes de pasar al siguiente.

- Este documento es la versión corta y práctica (tú, en la terminal).
- `PIAGENT.md` es la versión completa (runbook que puede ejecutar un agente por
  SSH): ahí están el detalle del rendimiento, el troubleshooting largo y las
  reglas del repo. Si algo se sale de aquí, mira ese fichero.

Ruta final en la Pi: todo vive en `~/NuriaAssistant/`. No la cambies, es el
`WorkingDirectory` de los servicios y donde el instalador busca el jar y el
backend.

---

## 0. Atajo: todo de una vez (opcional)

El repo trae un script que hace los pasos 4 y 7 sin copiar nada a mano
(requiere `rsync` en la Pi, paso 3):

```bash
./deploy/push-to-pi.sh ${PI_USER}@${PI_IP} --setup
```

- Valida que el jar sea ARM y aborta si es x86 (el fallo más típico), avisa si
  falta `config.properties` o `voice-backend/.env`.
- Copia el jar, `deploy/` y `voice-backend/` (con `.env` y modelos).
- Con `--setup` crea además el venv, instala las dependencias y ejecuta
  `install.sh --autologin` en la Pi (10–20 min en una Pi 3). Si `pip` falla en
  `openwakeword` (típico en ARM), reintenta solo con la vía ONNX, comprueba que
  el venv importe bien y sigue hasta instalar los servicios; si el venv queda
  incompleto, te avisa en vez de dejarlo a medias.

Otras opciones: `--no-models` (no copiar los 120 MB de modelos), `--dry-run`
(no cambia nada). Lo que sigue es la versión manual paso a paso, por si
prefieres controlarla tú.

## 0b. Claves API: dónde viven (importante)

| Clave | Dónde vive | ¿Va dentro del jar? |
|---|---|---|
| OpenWeather, Spotify, Telegram, calendario, `OWNER_NAME` | `src/main/resources/config.properties` | **Sí** |
| `GROQ_API_KEY` (la voz) | `voice-backend/.env` | No |

Es decir: **con el jar vale** para toda la interfaz (clima, Spotify, Telegram,
calendario), porque `config.properties` se empaqueta dentro cuando se compila
en el PC. La voz necesita además su `.env`, y el push lo copia junto al resto
(el jar y el `.env` nunca pasan por git: están ignorados).

Si algún día quieres cambiar una clave sin recompilar ni tocar el `.env`:
descoméntala en `~/.alpha/alpha.env` de la Pi (lo cargan los dos servicios) y
`sudo systemctl restart nuria-assistant nuria-voice`. Lo que ponga ahí gana
sobre el jar y sobre el `.env`.

Nunca compartas el jar con nadie ni lo subas a un repositorio público: lleva tus
claves dentro.

---

## 1. Rellena tus datos

```bash
export PI_IP=192.168.1.50     # <-- IP de la Pi (en la Pi: hostname -I)
export PI_USER=pi             # <-- usuario de la Pi
```

Comprueba el acceso antes de seguir (y sal con `exit`):

```bash
ssh ${PI_USER}@${PI_IP} 'uname -m'
# -> aarch64
```

Necesitas estar en la misma red que la Pi (para el `rsync` y para el
`spotify-callback` del móvil).

## 2. El jar ya está construido (PC)

Está en `target/NuriaAssistant-1.0-SNAPSHOT-all.jar`, con los nativos ARM y tu
`config.properties` dentro.

- Si **no** tocas código Java: sigue al paso 4.
- Si tocas algo: reconstruye **siempre con `-Ppi`** (sin él el jar sale x86 y en
  la Pi falla con *"JavaFX runtime components are missing"*):

```bash
cd <CARPETA_DEL_PROYECTO>
./mvnw clean package -Ppi -Djavafx.platform=linux-aarch64 -DskipTests
```

## 3. Paquetes base de la Pi (una vez)

Antes de copiar nada: el push necesita **`rsync` en la Pi** y la app necesita el
**JRE 21** y las librerías de JavaFX.

```bash
ssh ${PI_USER}@${PI_IP}
sudo apt update
sudo apt install -y openjdk-21-jre-headless libgtk-3-0 libgl1 mesa-utils \
     fontconfig libasound2 pulseaudio-utils alsa-utils network-manager rsync
sudo usermod -aG netdev,audio ${PI_USER}      # WiFi desde la pantalla + audio
exit
```

Comprueba (los grupos nuevos no entran en la sesión SSH hasta que vuelvas a
entrar):

```bash
ssh ${PI_USER}@${PI_IP} 'java -version; command -v rsync; python3 -V'
# -> openjdk 21.x, la ruta de rsync y Python 3.11 (Bookworm)
```

## 4. Copiar el sistema a la Pi (PC)

Desde la carpeta del proyecto:

```bash
ssh ${PI_USER}@${PI_IP} 'mkdir -p ~/NuriaAssistant'

rsync -av target/NuriaAssistant-1.0-SNAPSHOT-all.jar ${PI_USER}@${PI_IP}:NuriaAssistant/
rsync -av --delete deploy/ ${PI_USER}@${PI_IP}:NuriaAssistant/deploy/
rsync -av --delete --exclude '.venv' --exclude '__pycache__' \
      voice-backend/ ${PI_USER}@${PI_IP}:NuriaAssistant/voice-backend/
```

El último `rsync` se lleva `main.py`, `requirements.txt`, el `.env` con la clave
de Groq y los modelos (~120 MB): por eso tarda un poco.

Comprueba que ha llegado todo:

```bash
ssh ${PI_USER}@${PI_IP} 'ls -la ~/NuriaAssistant ~/NuriaAssistant/voice-backend ~/NuriaAssistant/voice-backend/models'
# -> NuriaAssistant-1.0-SNAPSHOT-all.jar, deploy/, voice-backend/
# -> main.py, .env, models/ (vosk, piper, hey_jarvis_v0.1.onnx)
```

## 5. Comprobar Python (Pi)

```bash
ssh ${PI_USER}@${PI_IP}
```

Ya dentro de la Pi:

```bash
python3 -V                                    # 3.11 en Bookworm: perfecto (3.9–3.12 sirve)
python3 -m venv /tmp/vtest && rm -rf /tmp/vtest && echo "venv OK"
python3 -m pip --version
```

- Si las tres responden: **adelante**.
- Si falla alguna:

```bash
sudo apt install -y python3-venv python3-pip portaudio19-dev espeak-ng
```

(`portaudio19-dev` es el micrófono y `espeak-ng` la voz de reserva si Piper
falla; se necesitan igualmente.)

## 6. Montar el backend de voz (Pi)

En una Pi 3 esto tarda bastante y un corte de SSH lo mata, así que hazlo dentro
de `tmux`:

```bash
sudo apt install -y tmux
tmux new -s voz          # si se corta el SSH: tmux attach -t voz
```

```bash
cd ~/NuriaAssistant/voice-backend
python3 -m venv .venv
./.venv/bin/pip install --upgrade pip
./.venv/bin/pip install -r requirements.txt
./.venv/bin/pip install piper-tts
```

Si `pip` se atasca en `openwakeword`/`tflite-runtime` (normal en ARM), usa la
vía ONNX, que es la que usa el proyecto:

```bash
./.venv/bin/pip install --no-deps openwakeword==0.6.0 onnxruntime scikit-learn scipy
```

Prueba que arranca (cargar los modelos tarda unos segundos):

```bash
./.venv/bin/python -m uvicorn main:app --host 0.0.0.0 --port 8090 &
sleep 8
curl -s http://127.0.0.1:8090/health
# -> {"ok":true,"runtime_running":false,"state":"stopped"}
kill %1
```

Sal de tmux con `exit` (`Ctrl-b d` lo deja abierto por si acaso).

No rellenes nada más del `.env`: las rutas de modelos ya son relativas y se
resuelven solas contra el directorio del backend.

## 7. Instalar los servicios y el autoarranque (Pi)

```bash
cd ~/NuriaAssistant/deploy
./install.sh ${PI_USER} --autologin
```

Esto hace todo de una vez: los dos servicios systemd (`nuria-voice` y
`nuria-assistant`), los arranca, activa el autologin del escritorio y deja
`~/.alpha/alpha.env` con la plantilla de claves (por si algún día quieres
cambiar algo sin recompilar). La UI queda en modo kiosco: sin barra de títulos,
sin botón de minimizar, tapando la barra del escritorio y sin cursor de ratón.

Lee la salida: si avisa de que falta el venv, el jar o el grupo `netdev`, para y
arréglalo (direcciones en el propio aviso; `netdev` necesita volver a entrar).

## 8. Reiniciar y comprobar (Pi → PC)

```bash
sudo reboot
```

Espera 1–2 minutos (una Pi 3 tarda) y desde el PC:

```bash
ssh ${PI_USER}@${PI_IP} 'systemctl --no-pager status nuria-voice nuria-assistant | grep -E "Active|Loaded"'
# -> Active: active (running)  en los dos

ssh ${PI_USER}@${PI_IP} 'journalctl -u nuria-assistant -b --no-pager | grep "Alpha UI up"'
# -> Alpha UI up in NNNN ms (kiosk=true)

ssh ${PI_USER}@${PI_IP} 'curl -s http://127.0.0.1:8090/health'
# -> {"ok":true,...}
```

En la pantalla: Alpha sola, reloj, clima, y el **orbe del micrófono azul cielo**
(si está gris, el backend no responde: paso 10).

## 9. Checklist final

- [ ] No hay barra de títulos, ni botones de ventana, ni cursor de ratón.
- [ ] `journalctl -u nuria-assistant -b | grep "Alpha UI up"` → `kiosk=true`.
- [ ] Orbe azul cielo (no gris).
- [ ] `curl http://127.0.0.1:8090/health` → `"ok":true`.
- [ ] Reloj y clima en pantalla, sin `SEVERE` en bucle en el log.
- [ ] Voz real: di *"hey Jarvis, ¿qué hora es?"* y contesta.
- [ ] Mensaje de prueba desde Telegram (algo como `/start` o cualquier texto).
- [ ] Alarma: puesta desde el reloj de la esquina, suena y se apaga/snoozea.
- [ ] Música: elige "Alpha" en Spotify desde el móvil y aparece la carátula.
- [ ] Apagar y encender: **todo vuelve solo**, sin tocar la terminal.

## 10. Si algo falla (lo primero que hay que mirar)

| Qué ves | Qué mirar |
|---|---|
| No aparece la UI | `systemctl status nuria-assistant`; sin autologin no hay `:0`; `journalctl -u nuria-assistant -b` |
| *"JavaFX runtime components are missing"* | se copió un jar sin `-Ppi` → recompila y repite el paso 4 |
| Orbe gris | `systemctl status nuria-voice`; `curl 127.0.0.1:8090/health`; falta `GROQ_API_KEY` o los modelos |
| Spotify suena pero Alpha no habla | `aplay -l`; si `paplay` sí suena, pon `PIPER_PLAY_COMMAND="paplay"` en `.env` y `sudo systemctl restart nuria-voice` |
| La ventana sale con barra de títulos | `systemctl show nuria-assistant -p Environment` (debe salir `KIOSK_MODE=true`) |
| El WiFi no cambia desde la pantalla | el usuario no está en `netdev` (cierra sesión y entra) |
| Va lento tras cambiar el jar | el archivo CDS es viejo: `rm ~/.alpha/alpha.jsa` y reinicia |

Más casos y el detalle de rendimiento (qué banderas hay puestas, cómo medirlas y
qué palancas quedan): `PIAGENT.md`, apartados 9 y 11.

## 11. Redesplegar solo el jar (lo más habitual)

Cuando cambies código Java, **no repitas el tutorial entero**:

```bash
cd <CARPETA_DEL_PROYECTO>
./mvnw clean package -Ppi -Djavafx.platform=linux-aarch64 -DskipTests
./deploy/push-to-pi.sh ${PI_USER}@${PI_IP} --no-models   # o el rsync manual del paso 4
ssh ${PI_USER}@${PI_IP} 'rm -f ~/.alpha/alpha.jsa; sudo systemctl restart nuria-assistant'
```

## 12. Deshacerlo todo (si hay que empezar de cero)

```bash
ssh ${PI_USER}@${PI_IP} 'cd ~/NuriaAssistant/deploy && ./install.sh --uninstall'
```

Después vuelve al paso 2. Para depurar sin el modo kiosco:

```bash
ssh ${PI_USER}@${PI_IP}
sudo systemctl stop nuria-assistant nuria-voice
cd ~/NuriaAssistant
DISPLAY=:0 KIOSK_MODE=true java -jar NuriaAssistant-1.0-SNAPSHOT-all.jar   # como en producción
# o para ver ventana y cursor:  DISPLAY=:0 java -jar NuriaAssistant-1.0-SNAPSHOT-all.jar
sudo systemctl start nuria-voice nuria-assistant
```

Recuerda: **raspotify ya está puesto**, no se reinstala ni se toca; solo se
comprueba con `systemctl is-active raspotify`.
