package com.example.nuriaassistant.services;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Service to synthesize and play spoken voice in natural Spanish.
 *
 * <p>Playback strategy (avoids JavaFX MediaPlayer which requires GStreamer MP3 codecs
 * that are often missing on Raspberry Pi OS):
 * <ol>
 *   <li>Download MP3 from Google Translate TTS and play it with {@code mpg123}.</li>
 *   <li>If {@code mpg123} is unavailable or the download fails, fall back to
 *       {@code espeak-ng} for offline text-to-speech synthesis.</li>
 * </ol>
 *
 * <p>Install on Raspberry Pi:
 * <pre>sudo apt install mpg123 espeak-ng</pre>
 */
public class TextToSpeechService {

    private final HttpClient httpClient;
    private final ExecutorService ttsExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tts-player-thread");
        t.setDaemon(true);
        return t;
    });

    /** Currently running playback process (mpg123 or espeak), so we can stop it. */
    private volatile Process currentProcess;

    public TextToSpeechService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();
    }

    /**
     * Speaks the given text in Spanish asynchronously.
     *
     * @param text Text to speak.
     */
    public void speak(String text) {
        if (text == null || text.isBlank()) {
            return;
        }

        String cleanText = cleanForSpeech(text);
        if (cleanText.isEmpty()) {
            return;
        }

        ttsExecutor.submit(() -> {
            // Stop any ongoing playback before starting a new one
            stopCurrentProcess();

            boolean played = tryGoogleTts(cleanText);
            if (!played) {
                fallbackEspeak(cleanText);
            }
        });
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Downloads MP3 from Google Translate TTS and plays it with mpg123.
     *
     * @return {@code true} if playback succeeded.
     */
    private boolean tryGoogleTts(String text) {
        try {
            String queryText = text.length() > 250 ? text.substring(0, 250) : text;
            String encodedText = URLEncoder.encode(queryText, StandardCharsets.UTF_8);
            String url = "https://translate.google.com/translate_tts?ie=UTF-8&tl=es&client=tw-ob&q=" + encodedText;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .timeout(Duration.ofSeconds(12))
                    .GET()
                    .build();

            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                System.err.println("TTS HTTP error: " + response.statusCode());
                return false;
            }

            File tempFile = File.createTempFile("nuria_tts_", ".mp3");
            tempFile.deleteOnExit();

            try (InputStream in = response.body();
                 FileOutputStream out = new FileOutputStream(tempFile)) {
                in.transferTo(out);
            }

            // Play with mpg123 (-q = quiet, no banner output)
            ProcessBuilder pb = new ProcessBuilder("mpg123", "-q", tempFile.getAbsolutePath());
            pb.redirectErrorStream(true);
            currentProcess = pb.start();
            int exitCode = currentProcess.waitFor();
            tempFile.delete();
            currentProcess = null;

            if (exitCode != 0) {
                System.err.println("TTS mpg123 exited with code: " + exitCode);
                return false;
            }
            return true;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            System.err.println("TTS Google TTS error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Fallback: synthesize speech offline via espeak-ng.
     */
    private void fallbackEspeak(String text) {
        try {
            // -v es = Spanish, -s 145 = slightly slower than default for better clarity
            ProcessBuilder pb = new ProcessBuilder("espeak-ng", "-v", "es", "-s", "145", text);
            pb.redirectErrorStream(true);
            currentProcess = pb.start();
            currentProcess.waitFor();
            currentProcess = null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.err.println("TTS espeak-ng error: " + e.getMessage());
        }
    }

    private void stopCurrentProcess() {
        Process p = currentProcess;
        if (p != null && p.isAlive()) {
            p.destroy();
        }
    }

    public void stop() {
        ttsExecutor.shutdownNow();
        stopCurrentProcess();
    }

    /** Placeholder for future wake-word chime. */
    public void playListeningChime() {
        // No-op until a chime sound file is bundled with the app
    }

    // -------------------------------------------------------------------------
    // Static utility
    // -------------------------------------------------------------------------

    public static String cleanForSpeech(String text) {
        if (text == null) return "";
        return text
                .replaceAll("\\*\\*", "")
                .replaceAll("\\*", "")
                .replaceAll("#\\w+", "")           // Remove hashtag words entirely (#noticias -> "")
                .replaceAll("#+\\s?", "")          // Remove remaining # heading markers
                .replaceAll("\\[([^\\]]+)\\]\\([^\\)]+\\)", "$1")
                .replaceAll("https?://\\S+", "")
                .replaceAll("[`_~]", "")
                .replaceAll("\\s{2,}", " ")        // Collapse multiple spaces
                .trim();
    }
}

