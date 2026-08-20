package com.example.nuriaassistant.services;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service to communicate with Google Gemini API (Gemini 3.6 Flash).
 * Features real-time Google Search Grounding for up-to-the-minute web information.
 */
public class GeminiService {

    private final String apiKey;
    private final HttpClient httpClient;
    private final String model;

    public GeminiService(String apiKey) {
        this(apiKey, "gemini-3.6-flash");
    }

    public GeminiService(String apiKey, String model) {
        this.apiKey = apiKey != null ? apiKey.trim() : "";
        this.model = (model != null && !model.isBlank()) ? model.trim() : "gemini-3.6-flash";
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * Asks Gemini a question with Google Search grounding enabled.
     *
     * @param query User's voice or text question.
     * @return CompletableFuture with Gemini's response string.
     */
    public CompletableFuture<String> askGemini(String query) {
        if (!isConfigured()) {
            return CompletableFuture.completedFuture("Por favor, configura tu clave GEMINI_API_KEY en config.properties.");
        }

        String systemPrompt = "Eres Nuria, un asistente de voz conciso, simpático y natural en español para una pantalla inteligente. "
                + "Responde de forma clara, directa y breve (máximo 2-3 frases) ideal para ser escuchada y leída.";

        String escapedQuery = escapeJson(query);
        String escapedSystem = escapeJson(systemPrompt);

        String jsonPayload = String.format("""
        {
          "contents": [
            {
              "role": "user",
              "parts": [
                { "text": "%s\\n\\nPregunta del usuario: %s" }
              ]
            }
          ],
          "tools": [
            { "google_search": {} }
          ]
        }
        """, escapedSystem, escapedQuery);

        String url = String.format("https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s", model, apiKey);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() == 200) {
                        String text = extractTextFromResponse(response.body());
                        return text != null && !text.isBlank() ? text : "No he podido encontrar una respuesta clara.";
                    } else {
                        System.err.println("Gemini API Error: " + response.statusCode() + " -> " + response.body());
                        return "Ha ocurrido un error al consultar a Gemini (Código " + response.statusCode() + ").";
                    }
                })
                .exceptionally(ex -> {
                    System.err.println("Gemini Request Exception: " + ex.getMessage());
                    return "Error de conexión con el asistente.";
                });
    }

    /**
     * Sends voice audio (WAV PCM) directly to Gemini Multi-modal API.
     */
    public CompletableFuture<String> askGeminiWithAudio(byte[] wavAudio) {
        if (!isConfigured()) {
            return CompletableFuture.completedFuture("Por favor, configura tu clave GEMINI_API_KEY en config.properties.");
        }

        String base64Audio = Base64.getEncoder().encodeToString(wavAudio);
        String systemPrompt = "Eres Nuria, un asistente de voz conciso, simpático y natural en español para una pantalla inteligente. "
                + "Escucha el audio del usuario, busca en Google si es necesario, y responde en español de forma directa y breve (2-3 frases).";

        String escapedSystem = escapeJson(systemPrompt);

        String jsonPayload = String.format("""
        {
          "contents": [
            {
              "role": "user",
              "parts": [
                { "text": "%s" },
                {
                  "inline_data": {
                    "mime_type": "audio/wav",
                    "data": "%s"
                  }
                }
              ]
            }
          ],
          "tools": [
            { "google_search": {} }
          ]
        }
        """, escapedSystem, base64Audio);

        String url = String.format("https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s", model, apiKey);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(25))
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() == 200) {
                        String text = extractTextFromResponse(response.body());
                        return text != null && !text.isBlank() ? text : "No he podido entender el audio con claridad.";
                    } else {
                        System.err.println("Gemini Audio Error: " + response.statusCode() + " -> " + response.body());
                        return "Ha ocurrido un error al procesar el audio con Gemini.";
                    }
                })
                .exceptionally(ex -> {
                    System.err.println("Gemini Audio Exception: " + ex.getMessage());
                    return "Error de conexión al enviar el audio.";
                });
    }

    /**
     * Extracts text candidate from Gemini API JSON response.
     */
    public static String extractTextFromResponse(String json) {
        if (json == null || !json.contains("\"candidates\"")) {
            return null;
        }

        // Find "parts": [ { "text": "..." } ]
        Pattern textPattern = Pattern.compile("\"text\"\\s*:\\s*\"((?:\\\\\"|[^\"])*)\"");
        Matcher matcher = textPattern.matcher(json);

        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String textPart = matcher.group(1)
                    .replace("\\n", "\n")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\");
            sb.append(decodeUnicode(textPart)).append("\n");
        }

        String result = sb.toString().trim();
        return !result.isEmpty() ? result : null;
    }

    private static String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String decodeUnicode(String input) {
        if (input == null || !input.contains("\\u")) {
            return input;
        }
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < input.length()) {
            if (input.charAt(i) == '\\' && i + 5 < input.length() && input.charAt(i + 1) == 'u') {
                try {
                    int code = Integer.parseInt(input.substring(i + 2, i + 6), 16);
                    sb.append((char) code);
                    i += 6;
                    continue;
                } catch (NumberFormatException ignored) {}
            }
            sb.append(input.charAt(i));
            i++;
        }
        return sb.toString();
    }
}
