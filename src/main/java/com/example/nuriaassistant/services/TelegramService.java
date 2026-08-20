package com.example.nuriaassistant.services;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service to connect Nuria Assistant with Telegram Bot API.
 * Allows receiving messages from anywhere in the world and displaying them on the screen.
 */
public class TelegramService {

    public record TelegramMessage(long updateId, long chatId, String senderName, String text) {}

    private final String botToken;
    private final String allowedChatId;
    private final Consumer<String> onMessageReceived;
    private final HttpClient httpClient;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread pollingThread;
    private long lastUpdateId = 0;

    public TelegramService(String botToken, String allowedChatId, Consumer<String> onMessageReceived) {
        this.botToken = botToken != null ? botToken.trim() : null;
        this.allowedChatId = allowedChatId != null && !allowedChatId.isBlank() ? allowedChatId.trim() : null;
        this.onMessageReceived = onMessageReceived;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Starts the long-polling background thread to receive Telegram messages.
     */
    public void start() {
        if (botToken == null || botToken.isBlank()) {
            System.out.println("TelegramService: No TELEGRAM_BOT_TOKEN provided. Service inactive.");
            return;
        }

        running.set(true);
        pollingThread = new Thread(this::pollUpdatesLoop, "telegram-bot-polling");
        pollingThread.setDaemon(true);
        pollingThread.start();
        System.out.println("TelegramService: Started polling for messages on Telegram.");
    }

    /**
     * Stops the Telegram polling service.
     */
    public void stop() {
        running.set(false);
        if (pollingThread != null) {
            pollingThread.interrupt();
        }
        System.out.println("TelegramService: Stopped.");
    }

    private void pollUpdatesLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                long offset = lastUpdateId > 0 ? lastUpdateId + 1 : 0;
                String url = String.format("https://api.telegram.org/bot%s/getUpdates?offset=%d&timeout=25", botToken, offset);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(35))
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    List<TelegramMessage> messages = parseUpdates(response.body());
                    for (TelegramMessage msg : messages) {
                        if (msg.updateId() > lastUpdateId) {
                            lastUpdateId = msg.updateId();
                        }

                        // Check authorization if configured
                        if (allowedChatId != null && !allowedChatId.equals(String.valueOf(msg.chatId()))) {
                            System.out.println("TelegramService: Ignored message from unauthorized Chat ID: " + msg.chatId());
                            continue;
                        }

                        handleIncomingMessage(msg);
                    }
                } else {
                    System.err.println("TelegramService: API returned status " + response.statusCode());
                    Thread.sleep(3000);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("TelegramService polling error: " + e.getMessage());
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void handleIncomingMessage(TelegramMessage msg) {
        String text = msg.text();
        if (text == null || text.isBlank()) {
            return;
        }

        if (text.equalsIgnoreCase("/start")) {
            sendReply(msg.chatId(), "👋 ¡Hola " + msg.senderName() + "! Escríbeme cualquier mensaje y se quedará fijado en la pantalla hasta que lo cierren o mandes /clear para borrarlo.");
            return;
        }

        if (text.equalsIgnoreCase("/clear") || text.equalsIgnoreCase("/borrar") ||
            text.equalsIgnoreCase("/limpiar") || text.equalsIgnoreCase("/quitar")) {
            if (onMessageReceived != null) {
                onMessageReceived.accept(""); // Clears notification
            }
            sendReply(msg.chatId(), "🗑️ Mensaje retirado de la pantalla");
            return;
        }

        // Format message for display on the screen
        String formattedDisplay = (msg.senderName() != null && !msg.senderName().isBlank())
                ? msg.senderName() + ": " + text
                : text;

        if (onMessageReceived != null) {
            onMessageReceived.accept(formattedDisplay);
        }

        // Reply confirmation back to Telegram
        sendReply(msg.chatId(), "✅ Mensaje mostrado en la pantalla");
    }

    /**
     * Sends a reply message to a Telegram chat.
     */
    public void sendReply(long chatId, String message) {
        if (botToken == null || botToken.isBlank() || message == null) {
            return;
        }

        try {
            String encodedText = URLEncoder.encode(message, StandardCharsets.UTF_8);
            String url = String.format("https://api.telegram.org/bot%s/sendMessage?chat_id=%d&text=%s",
                    botToken, chatId, encodedText);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            System.err.println("TelegramService: Failed to send reply: " + e.getMessage());
        }
    }

    /**
     * Parses Telegram getUpdates JSON response.
     */
    public static List<TelegramMessage> parseUpdates(String json) {
        List<TelegramMessage> result = new ArrayList<>();
        if (json == null || !json.contains("\"ok\"") || !json.contains("\"result\"")) {
            return result;
        }

        String[] chunks = json.split("\"update_id\"\\s*:\\s*");
        for (int i = 1; i < chunks.length; i++) {
            String chunk = chunks[i];
            try {
                // 1. Extract update_id
                Matcher idMatcher = Pattern.compile("^\\s*(\\d+)").matcher(chunk);
                if (!idMatcher.find()) {
                    continue;
                }
                long updateId = Long.parseLong(idMatcher.group(1));

                // 2. Only process if it contains a message with text
                if (!chunk.contains("\"text\"")) {
                    continue;
                }

                // 3. Extract chat_id
                long chatId = 0;
                Matcher chatMatcher = Pattern.compile("\"chat\"\\s*:\\s*\\{([\\s\\S]*?)\"id\"\\s*:\\s*(-?\\d+)").matcher(chunk);
                if (chatMatcher.find()) {
                    chatId = Long.parseLong(chatMatcher.group(2));
                }

                // 4. Extract sender first_name
                String senderName = "Telegram";
                Matcher nameMatcher = Pattern.compile("\"from\"\\s*:\\s*\\{([\\s\\S]*?)\"first_name\"\\s*:\\s*\"([^\"]+)\"").matcher(chunk);
                if (nameMatcher.find()) {
                    senderName = decodeUnicode(nameMatcher.group(2));
                }

                // 5. Extract message text
                String text = "";
                Matcher textMatcher = Pattern.compile("\"text\"\\s*:\\s*\"((?:\\\\\"|[^\"])*)\"").matcher(chunk);
                if (textMatcher.find()) {
                    text = decodeUnicode(textMatcher.group(1).replace("\\\"", "\""));
                }

                if (!text.isEmpty()) {
                    result.add(new TelegramMessage(updateId, chatId, senderName, text));
                }
            } catch (Exception ignored) {
            }
        }

        return result;
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
