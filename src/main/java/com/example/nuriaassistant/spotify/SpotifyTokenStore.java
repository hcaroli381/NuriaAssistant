package com.example.nuriaassistant.spotify;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Persists Spotify OAuth tokens as zero-dependency JSON at
 * ~/.alpha/spotify-tokens.json so reboots never require a new login:
 * the refresh token survives and access tokens are refreshed silently.
 * Writes are atomic (tmp file + move), same style as AlarmService.
 */
public class SpotifyTokenStore {

    private final Path storageFile;

    private String accessToken = "";
    private String refreshToken = "";
    private long expiresAtEpochMs = 0L;

    public SpotifyTokenStore() {
        this(Path.of(System.getProperty("user.home"), ".alpha", "spotify-tokens.json"));
    }

    public SpotifyTokenStore(Path storageFile) {
        this.storageFile = storageFile;
    }

    public String accessToken() {
        return accessToken;
    }

    public String refreshToken() {
        return refreshToken;
    }

    public long expiresAtEpochMs() {
        return expiresAtEpochMs;
    }

    public boolean hasRefreshToken() {
        return refreshToken != null && !refreshToken.isBlank();
    }

    public void save(String accessToken, String refreshToken, long expiresInSeconds) {
        if (refreshToken == null || refreshToken.isBlank()) {
            // Spotify does not always rotate the refresh token; keep the previous one.
            refreshToken = this.refreshToken;
        }
        this.accessToken = accessToken != null ? accessToken : "";
        this.refreshToken = refreshToken != null ? refreshToken : "";
        this.expiresAtEpochMs = System.currentTimeMillis() + expiresInSeconds * 1000L;
        persist();
    }

    /**
     * Loads persisted tokens. Returns true when a refresh token is available
     * (i.e. a silent session restore can be attempted).
     */
    public synchronized boolean load() {
        accessToken = "";
        refreshToken = "";
        expiresAtEpochMs = 0L;
        if (!Files.exists(storageFile)) {
            return false;
        }
        try {
            String json = Files.readString(storageFile, StandardCharsets.UTF_8);
            accessToken = extractString(json, "accessToken");
            refreshToken = extractString(json, "refreshToken");
            expiresAtEpochMs = extractLong(json, "expiresAtEpochMs");
        } catch (Exception e) {
            System.err.println("SpotifyTokenStore: failed to load tokens: " + e.getMessage());
            return false;
        }
        return hasRefreshToken();
    }

    /** Removes stored credentials (used when a refresh token stops working). */
    public synchronized void clear() {
        accessToken = "";
        refreshToken = "";
        expiresAtEpochMs = 0L;
        try {
            Files.deleteIfExists(storageFile);
        } catch (IOException e) {
            System.err.println("SpotifyTokenStore: failed to clear tokens: " + e.getMessage());
        }
    }

    private void persist() {
        try {
            Path parent = storageFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = storageFile.resolveSibling(storageFile.getFileName() + ".tmp");
            Files.writeString(tmp, toJson(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, storageFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, storageFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            System.err.println("SpotifyTokenStore: failed to save tokens: " + e.getMessage());
        }
    }

    private String toJson() {
        return "{\n"
                + "  \"accessToken\": \"" + escape(accessToken) + "\",\n"
                + "  \"refreshToken\": \"" + escape(refreshToken) + "\",\n"
                + "  \"expiresAtEpochMs\": " + expiresAtEpochMs + "\n"
                + "}";
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String extractString(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*\"((?:\\\\\"|[^\"])*)\"")
                .matcher(json);
        return m.find() ? m.group(1).replace("\\\"", "\"") : "";
    }

    private static long extractLong(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*(-?\\d+)")
                .matcher(json);
        return m.find() ? Long.parseLong(m.group(1)) : 0L;
    }
}
