package com.example.nuriaassistant.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javafx.scene.Cursor;
import javafx.scene.shape.Circle;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Pi hides the mouse pointer, but individual rows/keys ask for a hand
 * cursor; if that hint is not routed through {@link Cursors} the arrow comes
 * back on the touchscreen. These tests pin the policy.
 */
public class CursorsTest {

    @AfterEach
    void restoreDefault() {
        Cursors.setHidden(false);
    }

    @Test
    void tappableNodesGetAHandOutsideKioskMode() {
        Circle node = new Circle(4);
        Cursors.setInteractive(node);
        assertEquals(Cursor.HAND, node.getCursor());
    }

    @Test
    void tappableNodesGetNoCursorAtAllInKioskMode() {
        Cursors.setHidden(true);
        assertTrue(Cursors.isHidden());

        Circle node = new Circle(4);
        Cursors.setInteractive(node);
        assertEquals(Cursor.NONE, node.getCursor());
    }

    @Test
    void nullNodesAreIgnored() {
        assertDoesNotThrow(() -> Cursors.setInteractive(null));
    }
}
