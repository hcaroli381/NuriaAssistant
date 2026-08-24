package com.example.nuriaassistant.services;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Spawns the Python voice backend (uvicorn) as a child process of the Java
 * app so a single {@code java -jar} run contains the whole assistant system.
 *
 * Activation rules:
 * - Only used when {@code VOICE_BACKEND_URL} points at localhost (the usual
 *   same-box deployment); remote backends are never started locally.
 * - Backend directory resolution: {@code VOICE_BACKEND_DIR} config first,
 *   then conventional locations ({@code ./voice-backend}, {@code ~/voice-backend},
 *   {@code ~/.alpha/voice-backend}). A directory only counts when it has main.py.
 * - Python interpreter: {@code <dir>/.venv/bin/python} when present, otherwise
 *   {@code VOICE_PYTHON_BIN} or plain {@code python3}.
 * - One spawn attempt per application run unless the caller re-enables it;
 *   the child inherits stdout/stderr so backend logs reach the console, and
 *   it is destroyed on shutdown().
 */
public class VoiceBackendLauncher {

    private final String backendDirOverride;
    private final String pythonBinOverride;
    private final int port;
    private final boolean localBackend;

    private Process process;
    private boolean spawnAttempted = false;
    private boolean missingDirLogged = false;

    public VoiceBackendLauncher(String backendUrl, String backendDirOverride, String pythonBinOverride) {
        this.backendDirOverride = backendDirOverride;
        this.pythonBinOverride = pythonBinOverride;
        this.localBackend = backendUrl == null
                || backendUrl.isBlank()
                || backendUrl.contains("127.0.0.1")
                || backendUrl.contains("localhost");
        this.port = extractPort(backendUrl);
    }

    /**
     * Starts the backend once if it can be found locally and was not already
     * spawned. Safe to call repeatedly (e.g. from the polling loop): a missing
     * backend directory gives up quietly for this run, while a failed process
     * start is retried on the next call.
     *
     * @return true when a backend process is now running locally.
     */
    public synchronized boolean ensureRunning() {
        if (!localBackend || spawnAttempted || isAlive()) {
            return isAlive();
        }
        Path dir = resolveBackendDir();
        if (dir == null) {
            if (!missingDirLogged) {
                missingDirLogged = true;
                System.out.println("VoiceBackendLauncher: no local voice-backend/main.py found; "
                        + "set VOICE_BACKEND_DIR to enable auto-start from the jar.");
            }
            spawnAttempted = true;
            return false;
        }

        List<String> command = new ArrayList<>();
        command.add(resolvePython(dir));
        command.add("-m");
        command.add("uvicorn");
        command.add("main:app");
        command.add("--host");
        command.add("0.0.0.0");
        command.add("--port");
        command.add(String.valueOf(port));

        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(dir.toFile());
            pb.redirectErrorStream(true);
            pb.inheritIO();
            System.out.println("VoiceBackendLauncher: starting voice backend in " + dir
                    + " (port " + port + ")...");
            process = pb.start();
            spawnAttempted = true;
            return true;
        } catch (IOException e) {
            System.err.println("VoiceBackendLauncher: failed to start backend: " + e.getMessage()
                    + " — install deps with 'pip install -r requirements.txt' inside " + dir);
            return false;
        }
    }

    public synchronized boolean isAlive() {
        return process != null && process.isAlive();
    }

    /** Destroys the child backend process on application shutdown. */
    public synchronized void stop() {
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
            System.out.println("VoiceBackendLauncher: backend process stopped.");
        }
        process = null;
    }

    /** Resets the one-attempt guard (used by tests / manual retries). */
    synchronized void resetAttempts() {
        spawnAttempted = false;
    }
    private Path resolveBackendDir() {
        List<Path> candidates = new ArrayList<>();
        String home = System.getProperty("user.home");
        if (backendDirOverride != null && !backendDirOverride.isBlank()) {
            candidates.add(Path.of(backendDirOverride));
        } else {
            candidates.add(Path.of("voice-backend"));
            candidates.add(Path.of(home, "voice-backend"));
            candidates.add(Path.of(home, ".alpha", "voice-backend"));
        }
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate.resolve("main.py"))) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        return null;
    }

    private String resolvePython(Path dir) {
        if (pythonBinOverride != null && !pythonBinOverride.isBlank()) {
            return pythonBinOverride;
        }
        Path venvPython = dir.resolve(".venv").resolve("bin").resolve("python");
        if (Files.isRegularFile(venvPython)) {
            return venvPython.toString();
        }
        return "python3";
    }

    private static int extractPort(String backendUrl) {
        int fallback = 8090;
        if (backendUrl == null || backendUrl.isBlank()) {
            return fallback;
        }
        try {
            String withoutScheme = backendUrl.replaceFirst("^https?://", "");
            String authority = withoutScheme.split("/")[0];
            int colon = authority.lastIndexOf(':');
            if (colon >= 0) {
                return Integer.parseInt(authority.substring(colon + 1));
            }
        } catch (Exception ignored) {
            // fall through to default
        }
        return fallback;
    }
}
