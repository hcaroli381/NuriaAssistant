package com.example.nuriaassistant.spotify;

import com.google.zxing.common.BitMatrix;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class SpotifyQrGeneratorTest {

    @Test
    void generatesSquareMatrixForTypicalAuthUrl() {
        String authUrl = "https://accounts.spotify.com/authorize?response_type=code"
                + "&client_id=abc123def456&scope=user-read-currently-playing"
                + "%2Cuser-read-playback-state&redirect_uri=http%3A%2F%2F192.168.1.50%3A8888%2Fcallback"
                + "&state=xyz&show_dialog=false";

        BitMatrix matrix = SpotifyQrGenerator.generate(authUrl);

        assertNotNull(matrix, "a typical authorize URL must encode successfully");
        assertTrue(matrix.getWidth() >= 21, "QR codes are at least 21x21 modules");
        assertEquals(matrix.getWidth(), matrix.getHeight(), "QR output must be square");
    }

    @Test
    void matrixHasQuietZoneAndDarkModules() {
        BitMatrix matrix = SpotifyQrGenerator.generate("https://accounts.spotify.com/authorize?x=1");

        assertNotNull(matrix);
        boolean hasDark = false;
        for (int y = 0; y < matrix.getHeight(); y++) {
            for (int x = 0; x < matrix.getWidth(); x++) {
                if (matrix.get(x, y)) {
                    hasDark = true;
                }
            }
        }
        assertTrue(hasDark, "matrix must contain dark modules");
        // MARGIN hint = 2: the outer ring must stay light for scanners
        for (int i = 0; i < matrix.getWidth(); i++) {
            assertFalse(matrix.get(i, 0));
            assertFalse(matrix.get(0, i));
            assertFalse(matrix.get(i, matrix.getHeight() - 1));
            assertFalse(matrix.get(matrix.getWidth() - 1, i));
        }
    }

    @Test
    void blankOrNullInputReturnsNullInsteadOfThrowing() {
        assertNull(SpotifyQrGenerator.generate(null));
        assertNull(SpotifyQrGenerator.generate("   "));
    }
}
