package com.example.nuriaassistant.services;

import com.example.nuriaassistant.models.VoiceAssistantSnapshot;
import javafx.application.Platform;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * Lightweight client for the Python voice backend REST API (port 8090).
 * Uses a single shared HttpClient and plain string parsing to keep
 * allocations minimal on Raspberry Pi 3.
 */
public class VoiceAssistantService {

    private final HttpClient httpClient;
    private final String baseUrl;

    public VoiceAssistantService(String baseUrl) {
        this.baseUrl = baseUrl != null && !baseUrl.isBlank()
                ? baseUrl.replaceAll("/+$", "")
                : "http://127.0.0.1:8090";
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    /**
     * Asynchronously fetches the runtime state snapshot.
     *
     * @param onSuccess Called on the FX thread with the parsed snapshot,
     *                  or an offline snapshot if the backend is unreachable.
     */
    public void fetchState(Consumer<VoiceAssistantSnapshot> onSuccess) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/assistant/state"))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        return VoiceAssistantSnapshot.offlineSnapshot();
                    }
                    return parseSnapshot(response.body());
                })
                .exceptionally(ex -> VoiceAssistantSnapshot.offlineSnapshot())
                .thenAccept(snapshot -> Platform.runLater(() -> onSuccess.accept(snapshot)));
    }

    /**
     * Fire-and-forget request to start the voice runtime loop.
     */
    public void startRuntime() {
        postAsync("/assistant/start");
    }

    /**
     * Fire-and-forget request to stop the voice runtime loop.
     */
    public void stopRuntime() {
        postAsync("/assistant/stop");
    }

    private void postAsync(String path) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .exceptionally(ex -> null);
    }

    /**
     * Parses the flat JSON state payload without external dependencies.
     */
    static VoiceAssistantSnapshot parseSnapshot(String json) {
        if (json == null || json.isBlank()) {
            return VoiceAssistantSnapshot.offlineSnapshot();
        }

        String state = extractString(json, "state");
        double wakeScore = extractDouble(json, "wake_word_score");
        String transcript = extractString(json, "last_transcript");
        String reply = extractString(json, "last_reply");
        String error = extractString(json, "last_error");
        boolean running = extractBoolean(json, "running");

        return new VoiceAssistantSnapshot(state, wakeScore, transcript, reply, error, running, false);
    }

    private static String extractString(String json, String key) {
        String value = extractRawValue(json, key);
        if (value == null) {
            return "";
        }
        value = value.trim();
        if (value.startsWith("\"")) {
            int end = findStringEnd(value);
            return unescape(value.substring(1, end));
        }
        return value;
    }

    private static double extractDouble(String json, String key) {
        String value = extractRawValue(json, key);
        if (value == null) {
            return 0.0;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private static boolean extractBoolean(String json, String key) {
        String value = extractRawValue(json, key);
        return value != null && value.trim().equals("true");
    }

    /**
     * Finds the raw JSON value for a top-level string key. Returns null when absent.
     */
    private static String extractRawValue(String json, String key) {
        String pattern = "\"" + key + "\"";
        int index = json.indexOf(pattern);
        while (index >= 0) {
            int cursor = index + pattern.length();
            while (cursor < json.length() && Character.isWhitespace(json.charAt(cursor))) {
                cursor++;
            }
            if (cursor < json.length() && json.charAt(cursor) == ':') {
                cursor++;
                while (cursor < json.length() && Character.isWhitespace(json.charAt(cursor))) {
                    cursor++;
                }
                if (cursor >= json.length()) {
                    return null;
                }
                return json.substring(cursor, findValueEnd(json, cursor));
            }
            index = json.indexOf(pattern, cursor);
        }
        return null;
    }

    private static int findValueEnd(String json, int start) {
        char first = json.charAt(start);
        if (first == '"') {
            return start + 1 + findStringEnd(json.substring(start));
        }
        int end = start;
        while (end < json.length()) {
            char c = json.charAt(end);
            if (c == ',' || c == '}' || c == ']' || Character.isWhitespace(c)) {
                break;
            }
            end++;
        }
        return end;
    }

    /**
     * Given a string starting at the opening quote, returns the index of the closing quote.
     */
    private static int findStringEnd(String value) {
        for (int i = 1; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\') {
                i++;
                continue;
            }
            if (c == '"') {
                return i;
            }
        }
        return value.length();
    }

    private static String unescape(String s) {
        if (s.indexOf('\\') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(++i);
                switch (next) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    default -> sb.append(next);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
