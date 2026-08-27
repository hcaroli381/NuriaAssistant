package com.example.nuriaassistant.spotify;
import com.example.nuriaassistant.util.Log;

import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.Map;

/**
 * Encodes text (the Spotify authorization URL) into a QR BitMatrix using
 * ZXing core only. Returns null instead of throwing so callers can degrade
 * gracefully to showing the raw URL. Pure output keeps this unit-testable
 * without a running JavaFX toolkit.
 */
public final class SpotifyQrGenerator {

    private SpotifyQrGenerator() {
    }

    public static BitMatrix generate(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        try {
            return new QRCodeWriter().encode(
                    content,
                    com.google.zxing.BarcodeFormat.QR_CODE,
                    0,
                    0,
                    Map.of(
                            EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                            EncodeHintType.MARGIN, 2,
                            EncodeHintType.CHARACTER_SET, "UTF-8"));
        } catch (WriterException e) {
            Log.error("SpotifyQR", "SpotifyQrGenerator: failed to encode QR: " + e.getMessage());
            return null;
        }
    }
}
