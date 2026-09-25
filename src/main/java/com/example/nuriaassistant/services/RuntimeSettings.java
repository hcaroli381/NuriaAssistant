package com.example.nuriaassistant.services;
import com.example.nuriaassistant.util.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runtime overrides for configuration keys that must be changeable without
 * touching the deployed jar: the kiosk boots straight into the UI with no
 * keyboard and no terminal, so the calendar share link (and anything else added
 * later) is edited from the touchscreen or from Telegram.
 *
 * <p>Values are a flat string map persisted as zero-dependency JSON at
 * ~/.alpha/settings.json with atomic writes, in the same style as
 * {@code AlarmService} and {@code SpotifyTokenStore}. Precedence when reading a
 * key is environment variable &gt; system property &gt; this file &gt;
 * config.properties, so a value set on screen survives reboots but an explicit
 * deployment still wins.
 */
public class RuntimeSettings {

    /** Matches {@code "KEY": "value"} pairs, honouring escaped quotes/backslashes. */
    private static final Pattern ENTRY = Pattern.compile(
            "\"([A-Za-z0-9_.]+)\"\\s*:\\s*\"((?:\\\\\"|\\\\.|[^\"\\\\])*)\"");

    private final Path storageFile;
    private final Map<String, String> values = new LinkedHashMap<>();

    public RuntimeSettings() {
        this(Path.of(System.getProperty("user.home"), ".alpha", "settings.json"));
    }

    public RuntimeSettings(Path storageFile) {
        this.storageFile = storageFile;
        load();
    }

    /**
     * The stored override for a key, or null when it was never set on screen.
     */
    public synchronized String get(String key) {
        String value = values.get(key);
        return value == null || value.isBlank() ? null : value;
    }

    /** Stores an override and persists it; a blank value removes the key. */
    public synchronized void set(String key, String value) {
        if (key == null || key.isBlank()) {
            return;
        }
        if (value == null || value.isBlank()) {
            values.remove(key);
        } else {
            values.put(key, value.trim());
        }
        save();
    }

    public synchronized void load() {
        values.clear();
        if (!Files.exists(storageFile)) {
            return;
        }
        try {
            String json = Files.readString(storageFile, StandardCharsets.UTF_8);
            Matcher matcher = ENTRY.matcher(json);
            while (matcher.find()) {
                values.put(matcher.group(1), unescape(matcher.group(2)));
            }
        } catch (Exception e) {
            Log.error("Settings", "RuntimeSettings: failed to load overrides: " + e.getMessage());
        }
    }

    public synchronized void save() {
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
            Log.error("Settings", "RuntimeSettings: failed to save overrides: " + e.getMessage());
        }
    }

    String toJson() {
        StringBuilder sb = new StringBuilder("{\n");
        int i = 0;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (i++ > 0) {
                sb.append(",\n");
            }
            sb.append("  \"").append(entry.getKey()).append("\": \"")
              .append(escape(entry.getValue())).append('"');
        }
        sb.append("\n}");
        return sb.toString();
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
    }

    /** Single-pass unescape so {@code \\} and {@code \"} can never be mis-paired. */
    private static String unescape(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                char next = value.charAt(++i);
                sb.append(next == 'n' ? '\n' : next);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Precedence helper for callers reading a key: the value stored on screen,
     * otherwise the one shipped in config.properties / the environment.
     */
    public static String pick(String runtimeValue, String configuredValue) {
        if (runtimeValue != null && !runtimeValue.isBlank()) {
            return runtimeValue.trim();
        }
        return configuredValue;
    }
}
