# PIAGENT.md — Puesta en marcha de Alpha en la Raspberry Pi (una sola vez)

Este documento es un **runbook de una sola pasada**. Lo ejecuta un agente
(Freebuff, Claude Code, cualquiera con SSH) **desde el PC**, no desde la Pi, y
sirve para que una Raspberry Pi 3 recién instalada quede encendida y
funcionando sola: UI a pantalla completa + voz + Spotify + alarmas + calendario.

Reglas de uso:

1. **Un paso, una verificación.** No pases al siguiente paso sin ver el
   resultado esperado del anterior. Un fallo se arregla aquí, no más tarde.
2. **Nada queda instalado en la Pi.** El agente trabaja por SSH; al terminar no
   debe quedar ningún proceso suyo vivo. El único que corre 24/7 es Alpha.
3. **No toques nada que no esté en este documento.** Nada de actualizar
   paquetes del sistema, ni de "limpiar" modelos o servicios, ni de reescribir
   código en la Pi: el código se edita en el PC y se copia el jar.
4. **Pide al usuario lo que falte** antes de inventarlo (IP, usuario, si
   Spotify/el mando ya están configurados en el PC). No imprimas nunca el
   contenido de claves API ni de `voice-backend/.env`.

## 0. Datos que necesitas antes de empezar

| Dato | Ejemplo | Nota |
|---|---|---|
| IP de la Pi | `192.168.1.50` | fija o reservada en el router, si puede ser |
| Usuario de la Pi | `pi` | el mismo que autoentra en el escritorio |
| Acceso SSH | — | ya funcionando |

No hace falta pedir claves API: **el jar que se copia ya lleva dentro el
`config.properties` con el que se construyó en el PC** (OpenWeather, Spotify,
Telegram, calendario). Si esta Pi necesita valores distintos, se ponen en
`~/.alpha/alpha.env` (paso 7) sin recompilar nada.

La única excepción es **`GROQ_API_KEY`, que vive en `voice-backend/.env`** (la
usa el backend de voz, no la JVM). Esa viaja en el mismo `rsync`, y si además
se pone en `~/.alpha/alpha.env` funciona igual: cualquiera de los dos sitios
vale, y el fichero de `~/.alpha/` gana sobre el `.env`.

## 1. Resultado esperado (criterios de "hecho")

La Pi está terminada cuando, tras **apagarla y volver a encenderla**:

1. Alpha aparece sola, sin barra de títulos, sin botones de ventana, cubriendo
   los 1024x600 y sin cursor de ratón.
2. El log de la UI dice `Alpha UI up in <N> ms (kiosk=true)`.
3. El orbe del micrófono (arriba a la derecha) está **azul cielo**, no gris
   (gris = backend de voz inalcanzable).
4. `curl http://127.0.0.1:8090/health` responde `{"ok":true,...}`.
5. El reloj avanza, el clima aparece y `journalctl -u nuria-assistant` no
   muestra ningún `SEVERE` en bucle.

## 2. En el PC: construir el jar de la Pi

El PC compila; la Pi 3 nunca (un build de Maven allí cuesta minutos y necesita
el repo local). **Con `-Ppi` siempre**: el perfil mete los nativos
`linux-aarch64`; sin él el jar trae nativos x86 y en la Pi falla con
*"JavaFX runtime components are missing"*.

```bash
cd <PROYECTO>
./mvnw clean package -Ppi -Djavafx.platform=linux-aarch64 -DskipTests
```

Verificación de que el jar es de ARM:

```bash
unzip -p target/NuriaAssistant-1.0-SNAPSHOT-all.jar libglassgtk3.so > /tmp/g.so
file /tmp/g.so          # -> ELF 64-bit ... ARM aarch64
ls -la target/NuriaAssistant-1.0-SNAPSHOT-all.jar   # ~16-17 MB
```

Si cambias código Java, repite este paso: `./mvnw package` (sin `-Ppi`) deja un
jar x86 y sobrescribe el bueno.

## 3. En la Pi: sistema base

```bash
ssh <usuario>@<PI_IP>
sudo apt update
# rsync + openssh: los necesita deploy/push-to-pi.sh para copiar el jar y el
# backend desde el PC (ssh suele venir ya; rsync no siempre).
sudo apt install -y openjdk-21-jre-headless libgtk-3-0 libgl1 mesa-utils \
     fontconfig libasound2 pulseaudio-utils alsa-utils network-manager rsync
sudo usermod -aG netdev,audio <usuario>      # WiFi desde la pantalla + audio
```

Verificación:

```bash
java -version                     # openjdk 21.x  (NO 17, NO 25)
id -nG                            # tienen que aparecer netdev y audio
nmcli device status               # existe wlan0 y no está "unmanaged"
python3 -V                        # Bookworm trae 3.11; sirve 3.9-3.12
command -v rsync                  # sin rsync, el push desde el PC falla
```

Si `python3 -V` no responde o no crea entornos virtuales, **no se instala a
ciegas** aquí: el apartado 5b lo comprueba y el 5c instala solo lo que falte.

Los grupos nuevos no se aplican a la sesión SSH abierta: los servicios los
cogerán al reiniciar; para seguir trabajando, cierra sesión y vuelve a entrar.

## 4. En la Pi: audio y Spotify Connect

Raspotify y el audio **ya están resueltos en esta Pi: no se reinstalan y no hay
que tocar su configuración**. Aquí solo se comprueba que siguen vivos, porque
son los que sacan la voz de Alpha y la música por los altavoces:

```bash
systemctl is-active raspotify     # active
pactl info | head -3              # Server Name: ... on PipeWire ...
```

Si las dos respuestas son correctas, este apartado está terminado. La Pi
aparece como dispositivo "Alpha" en la app de Spotify y la UI de JavaFX solo
muestra lo que suena; el control sigue siendo el móvil.

## 5. En la Pi: backend de voz (venv + modelos + .env)

Alpha funciona sin backend de voz (reloj, clima, alarmas, calendario, Spotify),
pero el orbe se queda gris: para tener voz hay que dejar esto listo. Se instala
una sola vez y queda corriendo por systemd.

Todo el sistema vive en `~/NuriaAssistant/` en la Pi. Esa carpeta es el
`WorkingDirectory` del servicio y donde `install.sh` busca el jar y el backend,
así que respeta la ruta.

```bash
# 5a. DESDE EL PC, en un solo rsync: main.py, requirements.txt, .env (con la
#     clave de Groq) y models/ (~120 MB). El --exclude protege el .venv que ya
#     exista en la Pi de un borrado accidental con --delete.
rsync -av --delete --exclude '.venv' --exclude '__pycache__' \
      voice-backend/ <usuario>@<PI_IP>:NuriaAssistant/voice-backend/
```

Si prefieres no copiar los modelos (son ignorados por git), bórralos del rsync
con `--exclude 'models'`: el backend los descarga solo en el primer arranque de
la wake word, pero tarda bastante más.

```bash
# 5b. EN LA PI: comprobar Python antes de instalar nada
python3 -V                                   # ideal 3.9-3.12 (Bookworm trae 3.11)
python3 -m venv /tmp/vtest && rm -rf /tmp/vtest && echo "venv OK"
python3 -m pip --version

# 5c. EN LA PI: solo lo que haya fallado en 5b (y las deps del micro/voz de reserva)
sudo apt install -y python3-venv python3-pip portaudio19-dev espeak-ng
```

```bash
# 5d. EN LA PI: entorno Python del backend
cd ~/NuriaAssistant/voice-backend
python3 -m venv .venv
./.venv/bin/pip install --upgrade pip
./.venv/bin/pip install -r requirements.txt
./.venv/bin/pip install piper-tts
```

Si `pip` se atasca en `openwakeword`/`tflite-runtime` (no suele haber ruedas
ARM para todas las versiones de Python), instálalo por la vía ONNX, que es la
que usa este proyecto (`inference_framework="onnx"`):

```bash
./.venv/bin/pip install --no-deps openwakeword==0.6.0 onnxruntime scikit-learn scipy
```

El `.env` lleva `VOSK_MODEL_PATH=models/vosk/vosk-model-small-es-0.42`,
`PIPER_MODEL_PATH=models/piper/es_ES-davefx-medium.onnx` y
`PIPER_BIN=./.venv/bin/piper`; las rutas relativas se resuelven contra el
directorio del backend, así que no hay que tocarlas. **Ojo con claves
duplicadas**: python-dotenv deja ganar a la última, y un `VOICE_WAKE_THRESHOLD`
repetido a 0.03 provoca falsos despertares constantes.

Verificación (debe responder antes de seguir; cargar los modelos tarda):

```bash
cd ~/NuriaAssistant/voice-backend
./.venv/bin/python -m uvicorn main:app --host 0.0.0.0 --port 8090 &
sleep 8; curl -s http://127.0.0.1:8090/health; kill %1
# -> {"ok":true,"runtime_running":false,"state":"stopped"}
```

## 6. En el PC: copiar el jar y el instalador

Atajo: `./deploy/push-to-pi.sh <usuario>@<PI_IP> [--setup]` hace este apartado y
el 7 de una vez (con `--setup`, también el 5d), validando antes que el jar sea
ARM y avisando si falta `config.properties` o `voice-backend/.env`. Necesita
`rsync` en la Pi (apartado 3). Con `--setup`, si `pip` falla en `openwakeword`
reintenta solo la vía ONNX del apartado 5, comprueba que el venv importe y
sigue hasta `install.sh`; si el venv queda incompleto, lo avisa y no arranca el
servicio de voz a medias. Los comandos manuales son estos:

```bash
rsync -av target/NuriaAssistant-1.0-SNAPSHOT-all.jar <usuario>@<PI_IP>:NuriaAssistant/
rsync -av --delete deploy/ <usuario>@<PI_IP>:NuriaAssistant/deploy/
```

En la Pi tiene que quedar así:

```
~/NuriaAssistant/
├── NuriaAssistant-1.0-SNAPSHOT-all.jar
├── deploy/install.sh, nuria-assistant.service, nuria-voice.service
└── voice-backend/
    ├── main.py, requirements.txt, .env
    ├── .venv/bin/python
    └── models/{vosk,piper}/...
```

## 7. Servicios + autoarranque (install.sh)

```bash
ssh <usuario>@<PI_IP> 'cd ~/NuriaAssistant/deploy && ./install.sh <usuario> --autologin'
```

`install.sh` hace en una pasada: renderiza los dos units systemd
(`nuria-voice` + `nuria-assistant`), los habilita **y los arranca**, activa el
autologin del escritorio (`--autologin`, obligatorio: la UI solo se dibuja en
una sesión de escritorio en `:0`), crea `~/.alpha/alpha.env` con la plantilla de
claves y avisa si falta el venv, el jar o el grupo `netdev`.

Lo que deja ya configurado el unit de la UI (no lo toques a mano):

- `KIOSK_MODE=true` → ventana sin decorar, a pantalla completa, siempre encima
  y sin cursor de ratón.
- `JDK_JAVA_OPTIONS` con el perfil de Pi 3 (`-Xms64m -Xmx384m`,
  `-XX:+UseSerialGC`, `-XX:TieredStopAtLevel=1`, `-Dprism.order=sw`) y el
  archivo CDS (`~/.alpha/alpha.jsa`), que se crea en el primer arranque y se
  reutiliza a partir del segundo.
- `EnvironmentFile=-~/.alpha/alpha.env` en los dos units: claves propias de
  esta Pi sin recompilar y sin tocar el `.env` (descomenta solo lo que
  necesites y `systemctl restart nuria-assistant nuria-voice`). Lo que se ponga
  ahí gana sobre el `config.properties` del jar y sobre `voice-backend/.env`.

Verificación:

```bash
systemctl --no-pager status nuria-voice nuria-assistant
journalctl -u nuria-assistant -b --no-pager | grep "Alpha UI up"
# -> INFORMACIÓN: Alpha UI up in 12345 ms (kiosk=true)
```

## 8. Prueba de fuego: apagar y encender

```bash
sudo reboot
```

Espera a que vuelva el SSH (1-2 min en una Pi 3) y repite el checklist del
apartado 1. Si algo no aparece solo, mira el apartado 11 antes de tocar nada.

## 9. Rendimiento: lo que ya está hecho, cómo medirlo y qué palancas quedan

### Lo que ya está aplicado (no lo "optimices" otra vez)

| Palanca | Por qué |
|---|---|
| Se ejecuta el **jar prebuilt**, no Maven | un build en la Pi 3 cuesta minutos y necesita el repo local |
| `-XX:TieredStopAtLevel=1` | sin JIT C2: menos compilación en los núcleos lentos, arranque más corto |
| `-XX:+UseSerialGC` | el colector con menos sobrecarga en un heap de 384 MB |
| `-Xms64m -Xmx384m` | techo de memoria en una caja de 1 GB |
| `-Dprism.order=sw` | solo pipeline software: ni sondea GL/Mesa, que en la Pi 3 es frágil |
| **CDS** (`AutoCreateSharedArchive`) | el primer arranque escribe `~/.alpha/alpha.jsa`; los siguientes cargan las clases del jar ya verificadas, sin parsearlas |
| Trabajo periódico fuera del hilo de la UI | clima 30 min, Spotify 4 s, voz 1 s en un ticker daemon; solo el reloj usa `Timeline` |
| Estado duplicado y nodos cacheados | nunca se repinta estado idéntico y las animaciones van sobre texturas pre-rasterizadas |

Medición hecha en el PC con **las mismas banderas** (2 CPUs, render software,
mismo jar, `deploy/pi-sim.sh`):

| Configuración | Tiempo hasta la primera ventana |
|---|---|
| Sin CDS | 6,6 – 7,2 s |
| Con CDS caliente | 4,3 – 5,0 s |

Es decir, el archivo CDS ahorra del orden de un tercio del arranque **en esas
condiciones**. Son números de PC: en la Pi 3 hay que **volver a medirlos** con
los comandos de abajo antes de afirmar nada.

### Cómo medir en la Pi (y no adivinar)

```bash
# Arranque de la app (desde start() hasta la primera ventana):
journalctl -u nuria-assistant -b --no-pager | grep "Alpha UI up"
# Arranque completo del equipo (qué se llevó el tiempo):
systemd-analyze blame | head -10
# CPU y memoria de lo que está corriendo:
ps -o pid,rss,pcpu,cmd -C java -C python3
free -m; vcgencmd measure_temp; vcgencmd measure_clock arm
# ¿Se está usando de verdad el archivo CDS?
DISPLAY=:0 java -Xlog:cds=info -XX:+AutoCreateSharedArchive \
  -XX:SharedArchiveFile=$HOME/.alpha/alpha.jsa \
  -jar ~/NuriaAssistant/NuriaAssistant-1.0-SNAPSHOT-all.jar 2>&1 | grep -m2 "shared archive file"
# -> Opened shared archive file /home/<usuario>/.alpha/alpha.jsa
```

Si algo va raro, borra `~/.alpha/alpha.jsa` y se regenera en el siguiente
arranque; un archivo viejo o corrupto nunca bloquea el arranque, se ignora.

### Palancas que quedan (probar una cada vez, midiendo antes y después)

1. **`-Djavafx.animation.pulse=30`** (por defecto 60 Hz) en `JDK_JAVA_OPTIONS`:
   la mitad de pulsos por segundo. La UI es casi estática con fundidos lentos,
   así que debería notarse sobre todo en CPU en reposo. Verifica que las
   animaciones siguen viéndose bien; si notas tirones, vuelve a 60.
2. **Quitar `-Dprism.order=sw`** y dejar que pruebe el pipeline GL: a veces gana
   aceleración, a veces el driver de la Pi 3 lo estropea. Compara
   `vcgencmd measure_temp` y `top` a los cinco minutos de música sonando; si no
   mejora claro, quédate con software.
3. **`-Xmx256m`**: solo si `free -m` deja claro que hay margen. Un heap más
   pequeño arranca antes y genera menos GC, pero si la JVM ya vive rozando el
   techo provoca pausas. Mide con el reloj y el clima funcionando.
4. **zram** (`sudo apt install zram-tools`) para tener swap comprimido: en 1 GB
   de RAM ayuda con picos, a cambio de CPU. Mídelo, no lo instales "porque sí".
5. **journald en RAM** (`Storage=volatile` o `SystemMaxUse=30M`): no cambia el
   rendimiento, cambia la vida útil de la tarjeta SD en un equipo 24/7.

### Lo que NO hay que hacer

- No desactivar funciones para ganar milisegundos: voz, Spotify, calendario,
  Telegram, clima y alarmas son el producto, no adornos.
- No subir `-Xmx` por encima de 384m en una Pi de 1 GB.
- No ejecutar el jar a mano **y** tener `nuria-voice.service` activo a la vez:
  el puerto 8090 quedaría ocupado por los dos.
- No lanzar una segunda instancia de la UI: ocupa los puertos 8080/8888/8090.

### ¿Y Freebuff (o cualquier agente) en la propia Pi?

No hace falta y no se debe dejar corriendo:

- El agente que ejecuta este documento trabaja **desde el PC por SSH**; en la Pi
  solo quedan comandos sueltos, cero procesos residentes.
- Un agente 24/7 en una Pi 3 competiría por RAM y CPU con lo que de verdad
  importa (JVM + Vosk + Piper + la wake word) en un equipo que ya está
  terminado, y no aporta nada.
- Si hay que entrar a arreglar algo, se entra por SSH, se arregla y se sale.
  Antes de dejar cualquier cosa abierta, mira el presupuesto real con
  `ps -o rss= -C java -C python3` y `free -m`.

## 10. Arrancar o depurar a mano (cuando no quieres pasar por systemd)

```bash
sudo systemctl stop nuria-assistant nuria-voice
cd ~/NuriaAssistant
DISPLAY=:0 java -jar NuriaAssistant-1.0-SNAPSHOT-all.jar                     # ventana normal, con cursor
DISPLAY=:0 KIOSK_MODE=true java -jar NuriaAssistant-1.0-SNAPSHOT-all.jar     # kiosco, como en producción
sudo systemctl start nuria-voice nuria-assistant
```

Con el backend copiado en `./voice-backend` y el puerto libre, el propio jar
levanta uvicorn si no lo encuentra corriendo (útil para probar sin systemd).

## 11. Problemas típicos

| Síntoma | Qué mirar |
|---|---|
| La UI no aparece | `systemctl status nuria-assistant`; sin autologin no hay `:0` (install.sh lo comprueba); `journalctl -u nuria-assistant -b` |
| *"JavaFX runtime components are missing"* | se copió un jar sin `-Ppi` (nativos x86). Reconstruye y vuelve a copiar |
| Orbe gris | `curl 127.0.0.1:8090/health`; `systemctl status nuria-voice`; falta `GROQ_API_KEY` o los modelos en `.env` |
| Voz muda con el backend verde | el unit de la UI necesita `XDG_RUNTIME_DIR`/`DBUS_SESSION_BUS_ADDRESS` (ya están puestos) para llegar al audio de la sesión; si raspotify suena por los altavoces el audio del sistema está bien, así que mira el log del backend |
| Se despierta sola | sube `VOICE_WAKE_THRESHOLD` y/o `VOICE_RMS_THRESHOLD`; revisa claves duplicadas en `.env` |
| Spotify suena pero Alpha no habla | el backend reproduce con `aplay` (ALSA directo): comprueba `aplay -l` y que el dispositivo por defecto sea el de los altavoces; si `paplay` sí suena, pon `PIPER_PLAY_COMMAND="paplay"` en `.env` y `sudo systemctl restart nuria-voice` |
| La ventana tiene barra de títulos | `KIOSK_MODE` no llegó: `systemctl show nuria-assistant -p Environment` |
| El WiFi no cambia desde la pantalla | el usuario no está en `netdev` (re-login) o NetworkManager no está activo |
| Va lento tras cambiar el jar | el archivo CDS es viejo: `rm ~/.alpha/alpha.jsa` y reinicia |

## 12. Reglas del repositorio (por si hay que tocar código en el PC)

Si hay que editar código, sigue `CLAUDE.md`: paleta e iconos propios (nada de
emojis ni de glyphs de fuentes), nombre público **Alpha**, una sola clase de
glyph por forma, `KIOSK_MODE` apagado por defecto para desarrollo y
`./mvnw test` en verde (`FxmlContractTest` vigila el FXML y el CSS).
