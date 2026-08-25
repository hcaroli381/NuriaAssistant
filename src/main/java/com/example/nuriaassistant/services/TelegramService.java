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
 * Service to connect Alpha Assistant with Telegram Bot API.
 * Allows receiving messages from anywhere in the world and displaying them on the screen.
 */
public class TelegramService {

    public record TelegramMessage(long updateId, long chatId, String senderName, String text) {}

    /** A photo sent to the bot: fileId is resolved via getFile before downloading. */
    public record TelegramPhoto(long updateId, long chatId, String senderName, String fileId, boolean document) {}

    /** Handler invoked on the polling thread once photo bytes are downloaded. */
    public interface PhotoHandler {
        boolean onPhoto(TelegramPhoto photo, byte[] data);
    }

    /** Photos above this size are refused (protects Pi 3 RAM during decode). */
    private static final long MAX_PHOTO_BYTES = 8 * 1024 * 1024;

    private static final Pattern UPDATE_SPLIT = Pattern.compile("\"update_id\"\\s*:\\s*");

    private final String botToken;
    private final String allowedChatId;
    private final Consumer<String> onMessageReceived;
    private PhotoHandler photoHandler;
    private Consumer<String> commandHandler;
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

    /** Registers the handler that persists + displays downloaded photos. */
    public void setPhotoHandler(PhotoHandler photoHandler) {
        this.photoHandler = photoHandler;
    }

    /** Registers a handler for bot commands like /foto (text minus leading slash). */
    public void setCommandHandler(Consumer<String> commandHandler) {
        this.commandHandler = commandHandler;
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
                    String body = response.body();
                    List<TelegramMessage> messages = parseUpdates(body);
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

                    List<TelegramPhoto> photos = parsePhotoUpdates(body);
                    for (TelegramPhoto photo : photos) {
                        if (photo.updateId() > lastUpdateId) {
                            lastUpdateId = photo.updateId();
                        }

                        if (allowedChatId != null && !allowedChatId.equals(String.valueOf(photo.chatId()))) {
                            System.out.println("TelegramService: Ignored photo from unauthorized Chat ID: " + photo.chatId());
                            continue;
                        }

                        handleIncomingPhoto(photo);
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
            sendReply(msg.chatId(), "👋 ¡Hola " + msg.senderName() + "! Escríbeme cualquier mensaje y se quedará fijado en la pantalla hasta que lo cierren o mandes /clear para borrarlo. También puedes mandarme una foto para el marco, o /foto para verlo.");
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

        if (text.equalsIgnoreCase("/foto") || text.equalsIgnoreCase("/marco")) {
            if (commandHandler != null) {
                commandHandler.accept("foto");
                sendReply(msg.chatId(), "🖼️ Marco de fotos abierto en la pantalla");
            } else {
                sendReply(msg.chatId(), "🖼️ El marco de fotos no está disponible ahora mismo");
            }
            return;
        }

        // Alpha presents every remote message as her own speech on screen
        String formattedDisplay = text;

        if (onMessageReceived != null) {
            onMessageReceived.accept(formattedDisplay);
        }

        // Reply confirmation back to Telegram
        sendReply(msg.chatId(), "✅ Mensaje mostrado en la pantalla");
    }

    /**
     * Resolves the photo's file_id via getFile, downloads the bytes and hands
     * them to the registered handler. All of this happens on the polling
     * thread — never on the JavaFX thread.
     */
    private void handleIncomingPhoto(TelegramPhoto photo) {
        try {
            long fileSize = resolveFileSize(photo.fileId());
            if (fileSize > MAX_PHOTO_BYTES) {
                System.out.println("TelegramService: photo too large (" + fileSize + " bytes), refused.");
                sendReply(photo.chatId(), "❌ La foto es demasiado grande para el marco");
                return;
            }

            byte[] data = downloadFile(photo.fileId());
            if (data == null || data.length == 0) {
                sendReply(photo.chatId(), "❌ No pude descargar la foto");
                return;
            }

            boolean handled = photoHandler != null && photoHandler.onPhoto(photo, data);
            if (handled) {
                sendReply(photo.chatId(), "✅ Foto añadida al marco");
            } else {
                sendReply(photo.chatId(), "❌ No se pudo guardar la foto");
            }
        } catch (Exception e) {
            System.err.println("TelegramService: failed to process photo: " + e.getMessage());
            sendReply(photo.chatId(), "❌ No pude descargar la foto");
        }
    }

    /** Queries getFile for the file size (0 when unavailable). */
    private long resolveFileSize(String fileId) throws Exception {
        String url = String.format("https://api.telegram.org/bot%s/getFile?file_id=%s", botToken, fileId);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("getFile returned " + response.statusCode());
        }
        String body = response.body();
        Matcher sizeMatcher = Pattern.compile("\"file_size\"\\s*:\\s*(\\d+)").matcher(body);
        long size = sizeMatcher.find() ? Long.parseLong(sizeMatcher.group(1)) : 0L;
        if (!body.contains("\"ok\":true")) {
            throw new IllegalStateException("getFile not ok: " + body);
        }
        return size;
    }

    /** Downloads the actual file bytes for a given file_id. */
    private byte[] downloadFile(String fileId) throws Exception {
        String url = String.format("https://api.telegram.org/bot%s/getFile?file_id=%s", botToken, fileId);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("getFile returned " + response.statusCode());
        }

        Matcher pathMatcher = Pattern.compile("\"file_path\"\\s*:\\s*\"([^\"]+)\"").matcher(response.body());
        if (!pathMatcher.find()) {
            throw new IllegalStateException("getFile response missing file_path");
        }
        String filePath = pathMatcher.group(1);

        String downloadUrl = String.format("https://api.telegram.org/file/bot%s/%s", botToken, filePath);
        HttpRequest download = HttpRequest.newBuilder()
                .uri(URI.create(downloadUrl))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        HttpResponse<byte[]> bytes = httpClient.send(download, HttpResponse.BodyHandlers.ofByteArray());
        if (bytes.statusCode() != 200) {
            throw new IllegalStateException("file download returned " + bytes.statusCode());
        }
        return bytes.body();
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

    /**
     * Parses photo messages out of a getUpdates response. Supports both
     * compressed photos ("photo" size arrays — largest is chosen) and image
     * documents sent as files. Text-only updates are ignored here.
     */
    public static List<TelegramPhoto> parsePhotoUpdates(String json) {
        List<TelegramPhoto> result = new ArrayList<>();
        if (json == null || !json.contains("\"ok\"") || !json.contains("\"result\"")) {
            return result;
        }

        String[] chunks = UPDATE_SPLIT.split(json);
        for (int i = 1; i < chunks.length; i++) {
            String chunk = chunks[i];
            try {
                Matcher idMatcher = Pattern.compile("^\\s*(\\d+)").matcher(chunk);
                if (!idMatcher.find()) {
                    continue;
                }
                long updateId = Long.parseLong(idMatcher.group(1));

                String fileId = extractLargestPhotoId(chunk);
                boolean document = false;
                if (fileId == null) {
                    fileId = extractImageDocumentId(chunk);
                    document = true;
                }
                if (fileId == null) {
                    continue;
                }

                long chatId = 0;
                Matcher chatMatcher = Pattern.compile("\"chat\"\\s*:\\s*\\{([\\s\\S]*?)\"id\"\\s*:\\s*(-?\\d+)").matcher(chunk);
                if (chatMatcher.find()) {
                    chatId = Long.parseLong(chatMatcher.group(2));
                }

                String senderName = "Telegram";
                Matcher nameMatcher = Pattern.compile("\"from\"\\s*:\\s*\\{([\\s\\S]*?)\"first_name\"\\s*:\\s*\"([^\"]+)\"").matcher(chunk);
                if (nameMatcher.find()) {
                    senderName = decodeUnicode(nameMatcher.group(2));
                }

                result.add(new TelegramPhoto(updateId, chatId, senderName, fileId, document));
            } catch (Exception ignored) {
            }
        }

        return result;
    }

    /** Returns the file_id of the largest PhotoSize inside the "photo" array, or null. */
    private static String extractLargestPhotoId(String chunk) {
        Matcher arrayMatcher = Pattern.compile("\"photo\"\\s*:\\s*\\[([\\s\\S]*?)]").matcher(chunk);
        if (!arrayMatcher.find()) {
            return null;
        }
        String arrayBody = arrayMatcher.group(1);

        String bestId = null;
        long bestArea = -1;
        Matcher objectMatcher = Pattern.compile("\\{([^{}]*)}").matcher(arrayBody);
        while (objectMatcher.find()) {
            String body = objectMatcher.group(1);
            try {
                Matcher idMatcher = Pattern.compile("\"file_id\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
                Matcher wMatcher = Pattern.compile("\"width\"\\s*:\\s*(\\d+)").matcher(body);
                Matcher hMatcher = Pattern.compile("\"height\"\\s*:\\s*(\\d+)").matcher(body);
                if (!idMatcher.find()) {
                    continue;
                }
                long area = (wMatcher.find() ? Long.parseLong(wMatcher.group(1)) : 0)
                        * (hMatcher.find() ? Long.parseLong(hMatcher.group(1)) : 0);
                if (area > bestArea) {
                    bestArea = area;
                    bestId = idMatcher.group(1);
                }
            } catch (Exception ignored) {
            }
        }
        return bestId;
    }

    /** Returns the file_id of an attached image document, or null when absent/not an image. */
    private static String extractImageDocumentId(String chunk) {
        Matcher docMatcher = Pattern.compile("\"document\"\\s*:\\s*\\{([^{}]*)}").matcher(chunk);
        if (!docMatcher.find()) {
            return null;
        }
        String body = docMatcher.group(1);
        Matcher mimeMatcher = Pattern.compile("\"mime_type\"\\s*:\\s*\"image/([^\"]+)\"").matcher(body);
        Matcher idMatcher = Pattern.compile("\"file_id\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        if (mimeMatcher.find() && idMatcher.find()) {
            return idMatcher.group(1);
        }
        return null;
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
