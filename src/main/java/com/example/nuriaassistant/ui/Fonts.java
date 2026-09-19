package com.example.nuriaassistant.ui;

import com.example.nuriaassistant.util.Log;

import javafx.scene.text.Font;

/**
 * Alpha's own typeface, bundled in the jar (Space Grotesk, SIL OFL — see
 * {@code resources/.../fonts/OFL.txt}). Relying on the system font made every
 * screen look like a stock template; a bundled family gives the whole UI one
 * voice and renders identically on the Pi, macOS and Linux.
 *
 * <p>Both the weight and the size are set through CSS; {@link Font#loadFont}
 * only needs to register the family before the scene's stylesheets resolve it.
 */
public final class Fonts {

    /** Family name as declared inside the TTF files. */
    public static final String FAMILY = "Space Grotesk";

    /** Absolute resource paths: the fonts sit next to the UI package, not inside it. */
    private static final String[] FACES = {
            "/com/example/nuriaassistant/fonts/SpaceGrotesk-Regular.ttf",
            "/com/example/nuriaassistant/fonts/SpaceGrotesk-Medium.ttf",
            "/com/example/nuriaassistant/fonts/SpaceGrotesk-Bold.ttf"
    };

    private static boolean loaded = false;

    private Fonts() {
    }

    /** Registers every bundled face (idempotent, safe to call more than once). */
    public static synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        for (String face : FACES) {
            try (var stream = Fonts.class.getResourceAsStream(face)) {
                if (stream == null) {
                    Log.error("Fonts", "Missing bundled font: " + face);
                    continue;
                }
                if (Font.loadFont(stream, 12) == null) {
                    Log.error("Fonts", "Could not register font: " + face);
                }
            } catch (Exception e) {
                Log.error("Fonts", "Failed to load " + face + ": " + e.getMessage());
            }
        }
    }
}
