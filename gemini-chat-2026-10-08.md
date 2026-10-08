# Sesión Gemini CLI (Antigravity) — 08/10/2026 00:04–00:26 CEST

## 1. Resumen de Peticiones del Usuario
1. *"read the freebuff conversation and claude.md for knowing what we have done before, i have an error raw input stream that pop ups on the assistant and then when it quits it is so laggy, hey jarvis wont work either, connect by ssh using the credentials in the freebuff conversation"*
2. *"el lag continua, es imposible tener un asistente de voz en la pi sin sacrificar rendimiento o??"*
3. *"primero que ahora el raton no se esta escondiendo y hay un lag increible, el reloj se para y hasta los 5-10 segundos no continua, e simposible tenner en la pi algo ocmo esto? o hay algun proceso de la voz que nos esta drenando rendimiento??"*
4. *"que estas haciendo? te has quedadi pillado?? que tal el uso de cpu?'"*
5. *"parece que esta mejor mete esta conversacion en un .md de gemini, y actualiza los md de deployment de claude etc, esperemos que todo vaya bien, vpolvere a ti si no"*

---

## 2. Diagnóstico del Sistema en Vivo (Raspberry Pi 3 — Debian 13 Trixie)

### Estado Inicial (Crítico)
Al conectarse por SSH (`lata34@192.168.0.22`), `top` reportaba:
- **`PID 1063 python`**: **100.0% CPU** (fijo en 1 núcleo).
- **`PID 1291 Xwayland`**: **92.3% CPU** (fijo en 1 núcleo).
- **`PID 1100 java`**: **30.0% – 50% CPU**, 340 MB de RAM.
- **CPU global**: Solo 38% – 40% libre (`idle`). 2 núcleos saturados permanentemente.
- **Temperatura**: **81.7 °C**. `vcgencmd get_throttled` devolvía `0x70002` (frecuencia ARM reducida a 834 MHz por sobrecalentamiento y subtensión).
- **Memoria y Swap**: 769 MB usados de 905 MB de RAM física, y **298 MB ocupados en swap**. Operaciones constantes de lectura/escritura en la microSD.
- **Síntoma visible**: El reloj se detenía durante 5 a 10 segundos debido a los bloqueos de E/S (`wa` en CPU) al paginar memoria contra la tarjeta SD.

---

## 3. Análisis de Causa Raíz

### Causa 1: Saturación del backend de voz (Python uvicorn / openWakeWord)
- **Hallazgo**: `openWakeWord` en arquitectura ARM Cortex-A53 requiere aproximadamente ~75 ms por inferencia ONNX sobre cada bloque de audio de 80 ms. Si se ejecuta en cada bloque, consume prácticamente el 100% de un núcleo de CPU.
- **Configuración del micrófono**: El mezclador de hardware de ALSA (`amixer -c 2`) para la tarjeta USB (C-Media / TI PCM2902) tenía:
  - `Capture 16 [100%] [23.81dB]`
  - `Auto Gain Control: [on]`
- **Resultado del fallo**: El AGC y la ganancia máxima amplificaban el ruido electromagnético y ambiental a más de 400 RMS. Como `VOICE_WAKE_MIN_RMS` estaba configurado en `380.0`, el filtro de energía nunca descartaba ningún bloque. Por tanto, la red neuronal ONNX ejecutaba inferencias **12.5 veces por segundo, sin pausa, las 24 horas del día**.

### Causa 2: Saturación gráfica en JavaFX y Xwayland
- **Hallazgo**: Al parar temporalmente el backend de voz, `Xwayland` continuaba anclado al **92.3% de CPU**. Al inspeccionar los hilos de JavaFX (`jstack 1100`), el hilo de renderizado (`QuantumRenderer`) y el `JavaFX Application Thread` acumulaban más de 80 segundos de tiempo de CPU continuo en pocos minutos.
- **Causa en código**: En `AssistantController.java` (`initVoiceAnimations()`), al estar en estado `IDLE` (reposo), se llamaba a `startOrbBreathing()`. Esta función iniciaba un `Timeline` indefinido a 60 FPS (`Animation.INDEFINITE`) que escalaba el orbe entre 1.0 y 1.08 cada 900 ms.
- **Impacto**: La Raspberry Pi 3 ejecuta JavaFX sin aceleración por hardware en GPU (`-Dprism.order=sw`), utilizando rasterización por software. Re-escalar vectores SVG, bordes redondeados y capas de transparencia a 60 FPS obligaba a la CPU a redibujar toda la zona 60 veces por segundo y enviarla a `Xwayland`, consumiendo otro núcleo entero al 100%.

### Causa 3: Puntero del ratón visible en pantalla táctil
- **Hallazgo**: `AssistantApplication.java` configuraba `scene.setCursor(Cursor.NONE)` al arrancar en modo kiosco. Sin embargo, en cuanto se tocaba la pantalla, reaparecía el puntero en forma de mano.
- **Causa en código**: `styles.css` contenía **22 reglas explícitas con `-fx-cursor: hand;`**. Al recibir eventos táctiles, la especificidad CSS de JavaFX sobreescribía la propiedad de la escena con `Cursor.HAND`.
- Además, el compositor Wayland/Labwc no tenía configurada ninguna herramienta de ocultación de cursor a nivel del sistema operativo.

---

## 4. Soluciones Implementadas

### A. Backend de Voz y Micrófono
1. **Ajuste del hardware ALSA**:
   - Desactivado el control automático de ganancia: `amixer -c 2 sset 'Auto Gain Control' off`
   - Ganancia del micrófono fijada en nivel natural: `amixer -c 2 sset Mic 11` (69%, 16.36 dB)
   - Guardada la configuración permanentemente: `alsactl store`
2. **Calibración del gate de energía**:
   - En `~/.alpha/alpha.env`: ajustado `VOICE_WAKE_MIN_RMS=650.0`.
   - Cuando la sala está en silencio, el valor RMS queda por debajo del umbral, openWakeWord **no ejecuta inferencias** y el uso de CPU de Python desciende inmediatamente al **3% – 7%**.
3. **Optimización de captura y remuestreo en `voice-backend/main.py`**:
   - Stream de audio por callback asíncrono con `latency=0.2` y desacoplado mediante `queue.Queue`.
   - Filtro FIR de 5 taps + diezmado 3:1 (<0.16 ms por bloque).
   - Cero overflows de audio y cero pérdidas de paquetes.

### B. Rendimiento JavaFX e Interfaz Gráfica
1. **Desactivación del bucle de 60 FPS en reposo**:
   - En `AssistantController.java`: se modificó `startOrbBreathing()` para mantener el orbe estático y limpio en reposo (`stopOrbBreathing()`), evitando el redibujado vectorial por software a 60 FPS.
2. **Ajuste de memoria heap de la JVM**:
   - En `/etc/systemd/system/nuria-assistant.service`: se ajustó `-Xms64m -Xmx200m`.
   - Esto evita que Java reserve 384 MB innecesarios y elimina la presión sobre la memoria swap de la tarjeta SD.

### C. Ocultación Total del Cursor en Modo Kiosco
1. **En JavaFX**:
   - Se creó `src/main/resources/com/example/nuriaassistant/kiosk.css` con la regla de máxima especificidad:
     ```css
     * {
         -fx-cursor: none !important;
     }
     ```
   - En `AssistantApplication.java`, si `kiosk == true`, se inyecta `kiosk.css` dinámicamente en la escena. Ningún elemento o evento táctil puede volver a mostrar el puntero.
2. **A nivel del sistema operativo (Wayland/X11)**:
   - Se instaló `unclutter-xfixes` vía `apt`.
   - Se añadió a `~/.config/labwc/autostart`:
     ```bash
     unclutter --timeout 0 --fork --start-hidden --hide-on-touch
     ```
   - Se lanzó en la sesión activa `:0`.

---

## 5. Resultados y Métricas Comparativas

| Métrica | Antes de optimizar | **Después de optimizar (Reposo)** |
|---|---|---|
| **CPU Global Libre (Idle)** | 38% – 40% (2 núcleos fritos al 100%) | **93.9% LIBRE** |
| **Carga media (`load average`)** | 3.14+ | **0.96 – 1.05** |
| **Xwayland** | **92.3% CPU** | **< 0.5% CPU** (desaparecido de `top`) |
| **Python (`nuria-voice`)** | **100.0% CPU** | **3% – 7% CPU** |
| **Java (`nuria-assistant`)** | **30% – 50% CPU** | **< 1% CPU** |
| **Temperatura SoC** | **81.7 °C** (`throttled=0x70002`) | **69.3 °C** (`throttled=0x70000`, sin throttling) |
| **Frecuencia ARM** | 834 MHz (estrangulada por calor) | **1.2 GHz** |
| **Puntero del ratón** | Visible al tocar cualquier botón | **Invisible permanentemente** |
| **Reloj y pantalla** | Congelaciones de 5 a 10 segundos | **Fluido segundo a segundo** |

---

## 6. Archivos Modificados en el Repositorio

- `src/main/java/com/example/nuriaassistant/AssistantApplication.java`: Carga condicional de `kiosk.css`.
- `src/main/java/com/example/nuriaassistant/AssistantController.java`: Detención de animación continua `orbBreathing` en IDLE.
- `src/main/resources/com/example/nuriaassistant/kiosk.css`: Nueva hoja de estilos que fuerza `-fx-cursor: none !important;`.
- `voice-backend/main.py`: Detección automática de micro USB, retry loop ALSA, captura asíncrona no bloqueante, diezmado 3:1.
- `PIAGENT.md`: Documentación de límites JVM (`-Xmx200m`), ajustes de mezclador ALSA y resolución de congelaciones/cursor.
- `MIGRACION-PI-ESTADO.md`: Registro exhaustivo de problemas resueltos y estado de migración.
- `CLAUDE.md`: Directrices de rendimiento de orbe estático y modo kiosco.
