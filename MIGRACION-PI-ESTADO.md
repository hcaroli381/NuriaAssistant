# Migración a la Pi — estado y relevo

Notas de traspaso para la siguiente sesión. La Pi es `lata34@192.168.0.22`
(mi clave pública ya está autorizada; la contraseña **no** hace falta).
Sistema: **Debian 13 trixie**, Python **3.13.5**, no Raspberry Pi OS Bookworm
(el runbook `PIAGENT.md` asume 3.11 — importante para las ruedas de pip).

## Hecho y verificado

| Paso | Resultado |
|---|---|
| Acceso SSH | Clave `hugo.crrsc@gmail.com` autorizada en la Pi |
| Jar ARM | `~/NuriaAssistant/NuriaAssistant-1.0-SNAPSHOT-all.jar` (natives aarch64, al día) |
| Copia por rsync | jar + `deploy/` + `voice-backend/` (con los ~120 MB de modelos) |
| Paquetes base | Java 21.0.12.1, rsync, nmcli, grupos `netdev`+`audio` y `portaudio19-dev`, `espeak-ng`, `pulseaudio-utils` |
| venv del backend | Creado e importando todo (fastapi, uvicorn, httpx, vosk, sounddevice, openwakeword, piper…) |
| Servicios systemd | `nuria-voice` y `nuria-assistant` instalados, habilitados y **activos** |
| Autologin | `autologin-user=lata34` en lightdm; `install.sh --autologin` ejecutado |
| UI kiosco | `Alpha UI up in 23530 ms (kiosk=true)` en el journal |
| Micrófono | Captura OK (USB PnP Sound Device / PCM2902, índice 2). **Solo 48000/44100 Hz** |
| Altavoces | `aplay -q` por defecto funciona (card 0, bcm2835 Headphones) |

## Cambios de código (sin commitear)

`voice-backend/main.py`:

1. **`_Resampler`** (nuevo, solo numpy): banco polifásico sinc-veto (25 taps, más
   ancho si la relación no es entera) de la tasa del micro a 16 kHz.
   Verificado en la Pi: 1 kHz y 7 kHz preservados, 12 kHz atenuado −52 dB
   (48 kHz) y −75 dB (44,1 kHz), **error máximo 0,21 %** frente a la senoidal
   ideal, **1280 muestras exactas por bloque**, **2,66 ms por bloque de 80 ms**
   (3,3 % de un núcleo).
2. **`_open_input_stream()`**: intenta 16 kHz y, si el hardware lo rechaza
   (`paInvalidSampleRate`), cae a 48 kHz + remuestreo. Nueva variable
   `VOICE_CAPTURE_SAMPLE_RATE` (0 = auto) en `.env.example`.
3. **Overflow**: ya no deja el runtime clavado en `state: error` (era lo que
   pintaría el orbe rojo); lo registra en `last_error` y sigue.

`~/.alpha/alpha.env` en la Pi: `VOICE_WAKE_MIN_RMS=1000` (medido: el ruido de
sala es ~338 de mediana y 892 en p99, muy por encima del 150 por defecto, así
que la inferencia ONNX corría en **todos** los bloques).

## Problemas resueltos (Sesión 07/10/2026 23:30)

1. **Error "Error opening Raw input stream" en pantalla**:
   - **Causa raíz 1 (Doble proceso en boot)**: Java (`VoiceBackendLauncher`) intentaba auto-iniciar Python si el backend tardaba >15s en arrancar Vosk. Ambos procesos competían por ALSA `hw:2,0`, causando contención y ALSA error -71/-32.
   - **Causa raíz 2 (Fallo de PortAudio al abrir stream)**: `_open_input_stream` no reintentaba si ALSA estaba ocupado transitoriamente.
   - **Solución**:
     - En `~/.alpha/alpha.env`: `VOICE_BACKEND_DIR=none` para que Java nunca intente arrancar un segundo proceso (systemd ya gestiona `nuria-voice`).
     - En `VoiceBackendLauncher.java`: soporte explícito para `none`/`disabled`.
     - En `voice-backend/main.py`: `_open_input_stream` implementa búsqueda automática de dispositivo USB y bucle de reintento (hasta 5 intentos con espera exponencial de 1s) para absorber transitorios ALSA.

2. **Lag / 5% de audio perdido / Overflows eliminados**:
   - **Causa raíz**: El bucle usaba lectura síncrona bloqueante (`stream.read()`) + el remuestreo polifásico tardaba varios ms por bloque, cayendo a 88 ms por bloque de 80 ms.
   - **Solución**:
     - Se migró a captura por **callback en PortAudio** con `latency=0.2` (buffer ALSA de 200 ms que absorbe el jitter de split-transactions del hub USB).
     - Desacoplamiento mediante `queue.Queue`.
     - Filtro FIR anti-aliasing de 5 taps + diezmado 3:1 (<0.16 ms por bloque).
     - **Resultado verificado en la Pi**: **0 overflows** en pruebas de 15s y 30s continuas (188 bloques exactos en 15s). Cero pérdida de paquetes.

3. **Hey Jarvis no funcionaba**:
   - **Causa raíz 1**: `VOICE_WAKE_MIN_RMS` estaba puesto en `1000` en `~/.alpha/alpha.env`. La voz humana normal en esa sala produce entre 500 y 700 RMS, por lo que el gate de energía descartaba toda voz como "silencio" y nunca ejecutaba openWakeWord.
   - **Causa raíz 2**: `VOICE_WAKE_THRESHOLD=0.6` requería dos bloques consecutivos por encima de 0.6. Con 5% de pérdida de paquetes previa, era matemáticamente improbable que se activara.
   - **Causa raíz 3**: El modelo Groq en `.env` estaba configurado como `groq/compound-mini` (inexistente, HTTP 404).
   - **Solución**:
     - `VOICE_WAKE_MIN_RMS=380.0` (el ruido de fondo en reposo es ~270-350 RMS; la voz supera 450 RMS).
     - `VOICE_WAKE_THRESHOLD=0.45` con activación por pico fuerte (>= 0.45) o doble moderado (>= 0.35).
     - LLM actualizado a `qwen/qwen3.8-27b` en Groq (probado y respondiendo en <0.02s).
     - Flujo completo validado: texto -> LLM -> Piper TTS -> altavoz.

## Riesgo grave en la SD (decisión del usuario)

La tarjeta tiene **corrupción ext4**: `/var/lib/dpkg/info` es ilegible
(`ls` falla con *Mensaje erróneo* / EBADMSG), `dpkg -V` no funciona y faltaba el
fichero real `libportaudio.so.2.0.0` (lo restauré extrayéndolo del `.deb`).
`ldconfig` solo avisa de `libatopology.so.2`. Es decir: el daño existe y no lo he
reparado (haría falta un `fsck` **offline**, o reflashear).
Propuesta: `sudo touch /forcefsck` (la línea de comandos ya trae
`fsck.repair=yes`) y reiniciar. **No lo he hecho**: es reparar el sistema de
ficheros de su equipo y necesita su visto bueno.

## Problemas resueltos (Sesión 08/10/2026 00:20) — Congelaciones (5-10s), CPU al 94% IDLE y Cursor Oculto

### 1. Cuello de botella y congelaciones de 5-10s (CPU y Swap)
- **Diagnóstico en vivo**:
  - `top` mostraba dos procesos devorando núcleos completos simultáneamente: `python` al 100% y `Xwayland` al 92.3%.
  - Carga media (`load average`) en 3.14+, temperatura en **81.7°C** (`throttled=0x70002`: throttling térmico y de frecuencia ARM bajando a 834 MHz).
  - La memoria superaba el 1 GB físico (Java 340 MB + Python 140 MB + Xwayland 200 MB), volcando 300 MB a la tarjeta microSD en swap (`swpd: 298 MB`). La saturación de operaciones de E/S en la microSD congelaba el kernel y el reloj durante 5-10 segundos.

- **Causa raíz 1 (Proceso de voz / Python uvicorn)**:
  - En ALSA, el micrófono USB (Card 2) tenía ganancia de captura al 100% (+23.8 dB) y `Auto Gain Control` activado.
  - El ruido ambiente de fondo superaba de forma constante `VOICE_WAKE_MIN_RMS=380.0`.
  - Como el gate de energía nunca cerraba, openWakeWord ejecutaba inferencia de red neuronal ONNX cada 80 ms (12.5 veces por segundo, 78 ms por inferencia), quemando un núcleo al 100%.
  - **Solución**:
    - Se desactivó el AGC en ALSA: `amixer -c 2 sset 'Auto Gain Control' off`.
    - Se ajustó la ganancia de micro a un nivel natural: `amixer -c 2 sset Mic 11` (69%, 16.36 dB) y guardado con `alsactl store`.
    - Se calibró `VOICE_WAKE_MIN_RMS=650.0` en `~/.alpha/alpha.env`.
    - **Resultado**: En reposo (silencio), openWakeWord no ejecuta inferencia y Python consume solo **3% - 7% de CPU**.

- **Causa raíz 2 (JavaFX / Xwayland repintado continuo)**:
  - En `AssistantController.java` (`initVoiceAnimations()`), en estado `IDLE` se ejecutaba `startOrbBreathing()`: una animación continua e indefinida a 60 FPS (`Animation.INDEFINITE`) que escalaba el orbe de 1.0 a 1.08 cada 900 ms.
  - Con renderizado por software en la Pi (`-Dprism.order=sw`), escalar vectores y transparencias a 60 FPS forzaba a `Xwayland` al **92.3% de CPU** y a Java al **30% de CPU**.
  - **Solución**:
    - Se modificó `startOrbBreathing()` en `AssistantController.java` para mantener el orbe estático y limpio en reposo, deteniendo el Timeline continuo.
    - Se ajustó `-Xms64m -Xmx200m` en `nuria-assistant.service` para evitar que Java infle el heap y provoque swap.
    - **Resultado**: `Xwayland` cayó de **92.3% a < 0.5% de CPU** (desapareció de `top`) y Java a < 1% en reposo.

- **Métricas comparativas**:
  | Métrica | Antes | Después |
  |---|---|---|
  | CPU Libre (% IDLE) | 38% | **93.9%** |
  | CPU Xwayland | 92.3% | **< 0.5%** |
  | CPU Python | 100.0% | **3% - 7%** |
  | CPU Java (reposo) | 30% | **< 1%** |
  | Temperatura Pi | 81.7 °C (throttled) | **69.3 °C** (sin throttling) |
  | Comportamiento reloj | Congelado 5-10s | **Segundos continuos y fluidos** |

### 2. Cursor de ratón visible en pantalla táctil (Kiosk Mode)
- **Causa raíz**:
  - `styles.css` tenía 22 declaraciones explícitas con `-fx-cursor: hand;` en botones, iconos y tarjetas.
  - Al tocar la pantalla, JavaFX sobreescribía `scene.setCursor(Cursor.NONE)` con `Cursor.HAND`, dejando el puntero visible.
- **Solución**:
  - Se creó `src/main/resources/com/example/nuriaassistant/kiosk.css` con:
    ```css
    * {
        -fx-cursor: none !important;
    }
    ```
  - En `AssistantApplication.java`, cuando `kiosk == true`, se inyecta `kiosk.css` en la escena.
  - A nivel de SO / Wayland, se instaló `unclutter-xfixes` y se configuró en `~/.config/labwc/autostart`:
    ```bash
    unclutter --timeout 0 --fork --start-hidden --hide-on-touch
    ```
  - El puntero queda total y permanentemente invisible.

## Problemas resueltos (Sesión 08/10/2026 15:40) — Síntesis de Voz en Memoria, Activación Wake Word, undervoltage y Bucle "Pensando"

### 1. Desactivación de throttling por subtensión (`avoid_warnings=2`)
- **Problema**: La Pi sufría bajadas automáticas de frecuencia por undervoltage en la fuente de alimentación.
- **Solución**: Se añadió `avoid_warnings=2` en `/boot/firmware/config.txt` bajo la sección `[all]`. Esto suprime las restricciones automáticas de frecuencia de la GPU/CPU por avisos de voltaje.

### 2. Activación de "Hey Jarvis" natural y accesible
- **Causa raíz**: En la sesión anterior, para reducir el 100% de CPU de Python causado por la ganancia máxima de ALSA y AGC, se subió `VOICE_WAKE_MIN_RMS=650.0`. Sin embargo, la voz humana normal a 1-2 metros del micrófono produce entre 300 y 450 RMS. Con el umbral en 650.0, el gate de energía descartaba la voz humana y openWakeWord nunca recibía las palabras a menos que se gritara pegado al micrófono.
- **Solución**:
  - Se calibró la ganancia del micrófono USB a `Mic 10` (62%, +14.88 dB) con AGC desactivado, y se persistió con `alsactl store`. El ruido de fondo en reposo bajó a 80–130 RMS.
  - Se ajustó `VOICE_WAKE_MIN_RMS=220.0` y `VOICE_WAKE_THRESHOLD=0.35` en `~/.alpha/alpha.env`.
  - Ahora cualquier voz normal activa openWakeWord instantáneamente, mientras que el silencio se mantiene bajo 220 RMS manteniendo la CPU en reposo en ~2-5%.

### 3. Síntesis y Reproducción de Voz (Piper en memoria + PipeWire)
- **Causa raíz 1 (Latencia extrema)**: Cada respuesta ejecutaba el script CLI `piper` vía `subprocess.run()`. En la Raspberry Pi 3 (tarjeta microSD + CPU Cortex-A53), cada invocación tardaba **18 a 22 segundos solamente en arrancar Python, importar ONNX Runtime y cargar los 63 MB del modelo `es_ES-davefx-medium.onnx`** desde la SD. Una respuesta normal tardaba más de 1 minuto en generarse, dando la impresión de que no hablaba.
- **Causa raíz 2 (Audio mudo en systemd)**: `nuria-voice.service` carecía de `XDG_RUNTIME_DIR=/run/user/1000` y `DBUS_SESSION_BUS_ADDRESS`. La reproducción por `pw-play` fallaba por no poder conectar al daemon PipeWire de la sesión, y `aplay` enviaba ALSA directo a `hw:0,0` (jack 3.5mm) ignorando la salida activa (p. ej. pantalla HDMI).
- **Solución**:
  - En `voice-backend/main.py`: Se precarga `piper.PiperVoice.load()` una sola vez en memoria al iniciar `VoiceAssistantRuntime`. La síntesis de respuestas pasó de **21.6 segundos a solo 4.8 segundos (aceleración de 4.5x)**.
  - Se implementó `_play_wav()` con fallback automático ordenado: comando configurado (`pw-play`), `pw-play` nativo, y `aplay -q`.
  - Se añadieron `XDG_RUNTIME_DIR=/run/user/1000` y `DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/1000/bus` a `nuria-voice.service` (tanto en la Pi como en `deploy/nuria-voice.service`).

### 4. Corrección del bucle "Pensando" y repetición de respuesta
- **Causa raíz 1**: En `_run_loop()`, si el usuario activaba la captura o si había un despertar en falso pero Vosk devolvía un texto vacío (`transcript == ""`), el bloque `_transcribe()` había fijado `AssistantState.processing`, pero el bucle no contenía `else` para devolver el estado a `idle`. El asistente se quedaba congelado indefinidamente en `processing` ("pensando"), mostrando la tarjeta anterior.
- **Causa raíz 2**: Durante la síntesis y locución, el buffer de captura se llenaba de audio residual y el extractor de características de `openWakeWord` (`self._model`) mantenía acumulados los mel-espectrogramas del audio anterior. Al terminar, la wake word se disparaba inmediatamente de nuevo con el audio residual.
- **Solución**:
  - Se añadió `_flush_audio()` que vacía la cola de audio, resetea el array `_pending` y llama a `self._model.reset()`.
  - Se invoca `_flush_audio()` inmediatamente tras cada locución y cuando `transcript` resulta vacío, restableciendo explícitamente el estado a `idle`.
