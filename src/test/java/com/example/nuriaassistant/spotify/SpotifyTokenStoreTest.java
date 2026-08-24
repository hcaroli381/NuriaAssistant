package com.example.nuriaassistant.spotify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class SpotifyTokenStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void saveThenLoadRoundTripsTokens() {
        Path file = tempDir.resolve("spotify-tokens.json");
        SpotifyTokenStore store = new SpotifyTokenStore(file);

        store.save("access-1", "refresh-1", 3600);
        assertTrue(store.hasRefreshToken());
        assertTrue(Files.exists(file), "token file must be persisted");

        SpotifyTokenStore reloaded = new SpotifyTokenStore(file);
        assertTrue(reloaded.load(), "a stored refresh token must allow session restore");
        assertEquals("access-1", reloaded.accessToken());
        assertEquals("refresh-1", reloaded.refreshToken());
        assertTrue(reloaded.expiresAtEpochMs() > System.currentTimeMillis());
    }

    @Test
    void refreshWithoutRotationKeepsPreviousRefreshToken() {
        Path file = tempDir.resolve("spotify-tokens.json");
        SpotifyTokenStore store = new SpotifyTokenStore(file);

        store.save("access-1", "refresh-1", 3600);
        store.save("access-2", null, 3600);

        assertEquals("access-2", store.accessToken());
        assertEquals("refresh-1", store.refreshToken(),
                "Spotify does not always rotate the refresh token; it must be kept");
    }

    @Test
    void loadReturnsFalseWhenFileMissingOrEmpty() {
        SpotifyTokenStore missing = new SpotifyTokenStore(tempDir.resolve("nope.json"));
        assertFalse(missing.load());

        SpotifyTokenStore empty = new SpotifyTokenStore(tempDir.resolve("empty.json"));
        empty.save("", "", 3600);
        assertFalse(empty.load(), "blank tokens are equivalent to no session");
    }

    @Test
    void clearRemovesStoredSession() {
        Path file = tempDir.resolve("spotify-tokens.json");
        SpotifyTokenStore store = new SpotifyTokenStore(file);
        store.save("access", "refresh", 3600);

        store.clear();

        assertFalse(Files.exists(file));
        assertFalse(store.hasRefreshToken());
        assertFalse(new SpotifyTokenStore(file).load());
    }
}
