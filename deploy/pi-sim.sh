#!/usr/bin/env bash
# Approximate Raspberry Pi 3 (4x Cortex-A53 @ 1.2 GHz, 1 GB RAM,
# software-rendered JavaFX) performance on a dev machine.
#
# This is a *feel* simulation, not a benchmark: QEMU-style ARM emulation only
# proves code runs on the right architecture (and is slower than the Pi, so
# useless for speed). What actually reproduces the Pi's behaviour is
# replicating its constraints:
#
#   - two logical CPUs   -> approximates the slow A53 cores (taskset)
#   - capped heap        -> the 1 GB box with the deployed -Xmx384m profile
#   - software rendering -> the Pi has no GPU path for JavaFX
#   - C1-only JIT        -> the deployed profile (no C2 on the Pi)
#
# Usage:
#   deploy/pi-sim.sh                        # ./mvnw javafx:run from project root
#   deploy/pi-sim.sh target/NuriaAssistant-1.0-SNAPSHOT-all.jar   # run a fat jar
#
# Tune with env vars:
#   PI_SIM_CPUS="0,1"     CPU affinity (default: two logical CPUs)
#   PI_SIM_XMX="384m"     max heap (default: matches the Pi deployment profile)
set -euo pipefail

PI_CPUS="${PI_SIM_CPUS:-0,1}"
PI_XMX="${PI_SIM_XMX:-384m}"
JVM_FLAGS="-Xms32m -Xmx${PI_XMX} -XX:+UseSerialGC -XX:TieredStopAtLevel=1"
PRISM_FLAGS="-Dprism.forceSw=true"

if [[ $# -ge 1 && -f "$1" ]]; then
    echo "Pi-sim: running fat jar  cpus=${PI_CPUS}  heap=${PI_XMX}  sw-rendering"
    exec taskset -c "${PI_CPUS}" env JDK_JAVA_OPTIONS="${JVM_FLAGS}" \
        java ${PRISM_FLAGS} -jar "$1"
fi

echo "Pi-sim: ./mvnw javafx:run  cpus=${PI_CPUS}  heap=${PI_XMX}  sw-rendering"
exec taskset -c "${PI_CPUS}" env JDK_JAVA_OPTIONS="${JVM_FLAGS}" \
    ./mvnw ${PRISM_FLAGS} javafx:run
