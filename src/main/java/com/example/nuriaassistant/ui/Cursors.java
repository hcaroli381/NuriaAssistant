package com.example.nuriaassistant.ui;

import javafx.scene.Cursor;
import javafx.scene.Node;

/**
 * Cursor policy for the touch screen.
 *
 * <p>The Pi has no mouse, so in kiosk mode the pointer is hidden entirely
 * ({@code Scene.setCursor(Cursor.NONE)}) — but rows, day chips and keyboard
 * keys used to set {@link Cursor#HAND} on themselves, which would bring the
 * arrow straight back. Every "this is tappable" hint therefore goes through
 * {@link #setInteractive(Node)}, which honours the kiosk flag.</p>
 */
public final class Cursors {

    private static boolean hidden = false;

    private Cursors() {
    }

    /**
     * Set once at startup, before the scene is built, from {@code KIOSK_MODE}.
     */
    public static void setHidden(boolean hideCursor) {
        hidden = hideCursor;
    }

    public static boolean isHidden() {
        return hidden;
    }

    /** Marks a node as tappable: a hand, or nothing at all on the touchscreen. */
    public static void setInteractive(Node node) {
        if (node != null) {
            node.setCursor(hidden ? Cursor.NONE : Cursor.HAND);
        }
    }
}
