package com.example.nuriaassistant.services;
import com.example.nuriaassistant.util.Log;

import com.example.nuriaassistant.models.CalendarEvent;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Consumer;

/**
 * Read-only iCloud calendar client. Fetches a public .ics share link every
 * ~15 minutes on the shared daemon ticker, caches the raw payload at
 * ~/.alpha/calendar.ics (atomic writes) so reboots render instantly even
 * offline, and parses occurrences off the FX thread. A missing/blank
 * CALENDAR_ICS_URL disables the feature entirely (silently off by design).
 */
public class CalendarService {

    private static final int CONNECT_TIMEOUT_S = 8;
    private static final int REQUEST_TIMEOUT_S = 15;
    /** Parse window: recent past (multi-day events still running) to ~a year ahead. */
    private static final int WINDOW_BACK_DAYS = 7;
    private static final int WINDOW_FORWARD_DAYS = 370;

    private final HttpClient httpClient;
    private final String icsUrl;
    private final Path cacheFile;

    public CalendarService(String icsUrl) {
        this(icsUrl, Path.of(System.getProperty("user.home"), ".alpha", "calendar.ics"));
    }

    public CalendarService(String icsUrl, Path cacheFile) {
        this.icsUrl = normalizeScheme(icsUrl);
        this.cacheFile = cacheFile;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_S))
                .build();
    }

    /**
     * iCloud share links come as webcal:// (a calendar-app alias for HTTPS).
     * Java's HttpClient rejects unknown schemes, so map it to https://.
     */
    private static String normalizeScheme(String url) {
        if (url == null) {
            return "";
        }
        String trimmed = url.trim();
        if (trimmed.regionMatches(true, 0, "webcal://", 0, "webcal://".length())) {
            return "https://" + trimmed.substring("webcal://".length());
        }
        return trimmed;
    }

    public boolean isConfigured() {
        return !icsUrl.isEmpty();
    }

    /**
     * Cheap sanity check for a link typed/sent in by hand (Telegram
     * {@code /calendario}): a share URL needs a scheme and a host. It cannot
     * prove the feed is readable — {@link #fetchNow()} does that — but it keeps
     * a pasted note or a phone's shared text from being stored as a URL.
     */
    public static boolean isPlausibleShareLink(String url) {
        if (url == null) {
            return false;
        }
        String trimmed = url.trim();
        if (trimmed.length() < 12 || trimmed.contains(" ")) {
            return false;
        }
        String lower = trimmed.toLowerCase(java.util.Locale.ROOT);
        if (!lower.startsWith("webcal://") && !lower.startsWith("https://") && !lower.startsWith("http://")) {
            return false;
        }
        try {
            URI uri = URI.create(normalizeScheme(trimmed));
            String host = uri.getHost();
            return host != null && host.contains(".");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Synchronous fetch used when a link is configured at runtime (Telegram):
     * the sender gets a real answer — how many events were read — instead of a
     * silent "saved". Persists the payload so the reboot render is instant.
     *
     * @throws IllegalStateException when the URL does not answer with a calendar.
     */
    public List<CalendarEvent> fetchNow() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(icsUrl))
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_S))
                .header("Accept", "text/calendar")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("respuesta HTTP " + response.statusCode());
        }
        String body = response.body();
        if (body == null || !body.contains("BEGIN:VCALENDAR")) {
            throw new IllegalStateException("el enlace no devuelve un calendario");
        }
        persist(body);
        return parseWindow(body);
    }

    /**
     * Parses the cached payload from disk (boot-time instant render).
     *
     * @return Events when a cache exists, otherwise an empty list.
     */
    public List<CalendarEvent> loadCached() {
        if (!Files.exists(cacheFile)) {
            return List.of();
        }
        try {
            return parseWindow(Files.readString(cacheFile, StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.error("Calendar", "CalendarService: failed to read cache: " + e.getMessage());
            return List.of();
        }
    }

    /**
     * Fetches the remote feed, persists it atomically and delivers the parsed,
     * time-sorted event list. Callbacks run on a worker thread — wrap with
     * Platform.runLater before touching any scene graph node.
     */
    public void fetchAsync(Consumer<List<CalendarEvent>> onSuccess, Consumer<Throwable> onError) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(icsUrl))
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_S))
                .header("Accept", "text/calendar")
                .GET()
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        throw new IllegalStateException("HTTP " + response.statusCode());
                    }
                    String body = response.body();
                    persist(body);
                    return parseWindow(body);
                })
                .whenComplete((events, error) -> {
                    if (error != null) {
                        Log.error("Calendar", "CalendarService: fetch failed: "
                                + (error.getCause() != null ? error.getCause().getMessage() : error.getMessage()));
                        if (onError != null) {
                            onError.accept(error.getCause() != null ? error.getCause() : error);
                        }
                    } else if (onSuccess != null) {
                        onSuccess.accept(events);
                    }
                });
    }

    private List<CalendarEvent> parseWindow(String icsBody) {
        LocalDate today = LocalDate.now();
        return CalendarIcsParser.parse(icsBody,
                today.minusDays(WINDOW_BACK_DAYS),
                today.plusDays(WINDOW_FORWARD_DAYS));
    }

    private void persist(String body) {
        try {
            Path parent = cacheFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = cacheFile.resolveSibling(cacheFile.getFileName() + ".tmp");
            Files.writeString(tmp, body, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, cacheFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, cacheFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Log.error("Calendar", "CalendarService: failed to cache feed: " + e.getMessage());
        }
    }
}
