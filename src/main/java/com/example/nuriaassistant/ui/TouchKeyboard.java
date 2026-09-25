package com.example.nuriaassistant.ui;

import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * On-screen keyboard for the touchscreen. The Pi has no keyboard, so the WiFi
 * password (and any future text entry in a setup flow) has to be typed with a
 * finger: every key is a plain {@link Label} on a 46px touch target, styled by
 * {@code .keyboard-key} in styles.css — the same hand as every other control.
 *
 * <p>The keyboard owns no text: it only reports key presses, so the caller
 * decides how to buffer and mask them.
 */
public class TouchKeyboard extends VBox {

    /** Receives every key press: a printable character, or a backspace. */
    public interface KeyHandler {
        void onCharacter(char c);

        void onBackspace();
    }

    private static final String[] LETTER_ROWS = {"qwertyuiop", "asdfghjkl"};
    private static final String[] SYMBOL_ROWS = {"1234567890", "-_=+!@#$%&*", ".,:;?/'\"()[]{}<>"};

    private final KeyHandler handler;
    private final VBox rowsBox = new VBox(6);

    private boolean shifted = false;
    private boolean symbols = false;

    public TouchKeyboard(KeyHandler handler) {
        this.handler = handler;
        setSpacing(6);
        setAlignment(Pos.CENTER);
        getChildren().add(rowsBox);
        rebuild();
    }

    /**
     * Rebuilds the key rows for the active layout. Called once per mode switch
     * (and after a shift, which releases by itself like on a phone): thirty
     * labels is nothing next to a finger tap.
     */
    private void rebuild() {
        rowsBox.getChildren().clear();
        if (symbols) {
            for (String row : SYMBOL_ROWS) {
                HBox keys = newRow();
                for (char c : row.toCharArray()) {
                    keys.getChildren().add(characterKey(c));
                }
                rowsBox.getChildren().add(keys);
            }
            HBox bottom = newRow();
            bottom.getChildren().addAll(
                    actionKey("ABC", () -> {
                        symbols = false;
                        rebuild();
                    }),
                    spaceKey(),
                    backspaceKey());
            rowsBox.getChildren().add(bottom);
            return;
        }

        for (String row : LETTER_ROWS) {
            HBox keys = newRow();
            for (char c : row.toCharArray()) {
                keys.getChildren().add(characterKey(caseOf(c)));
            }
            rowsBox.getChildren().add(keys);
        }

        HBox third = newRow();
        third.getChildren().add(actionKey("\u21e7", () -> {
            shifted = !shifted;
            rebuild();
        }));
        for (char c : "zxcvbnm".toCharArray()) {
            third.getChildren().add(characterKey(caseOf(c)));
        }
        third.getChildren().add(backspaceKey());
        rowsBox.getChildren().add(third);

        HBox bottom = newRow();
        bottom.getChildren().addAll(
                actionKey("?123", () -> {
                    symbols = true;
                    rebuild();
                }),
                spaceKey(),
                characterKey('.'));
        rowsBox.getChildren().add(bottom);
    }

    private char caseOf(char c) {
        return shifted ? Character.toUpperCase(c) : c;
    }

    private static HBox newRow() {
        HBox row = new HBox(6);
        row.setAlignment(Pos.CENTER);
        return row;
    }

    /** A letter/digit/symbol key; letters release the shift after one press. */
    private Label characterKey(char c) {
        Label key = styledKey(String.valueOf(c), "keyboard-key");
        key.setOnMouseClicked(e -> {
            handler.onCharacter(c);
            if (shifted && Character.isLetter(c)) {
                shifted = false;
                rebuild();
            }
        });
        return key;
    }

    private Label spaceKey() {
        Label key = styledKey("espacio", "keyboard-key", "keyboard-key-wide");
        key.setOnMouseClicked(e -> handler.onCharacter(' '));
        return key;
    }

    private Label backspaceKey() {
        Label key = styledKey("\u232b", "keyboard-key", "keyboard-key-accent");
        key.setOnMouseClicked(e -> handler.onBackspace());
        return key;
    }

    private Label actionKey(String text, Runnable action) {
        Label key = styledKey(text, "keyboard-key", "keyboard-key-accent");
        key.setOnMouseClicked(e -> action.run());
        return key;
    }

    private static Label styledKey(String text, String... styleClasses) {
        Label key = new Label(text);
        key.getStyleClass().addAll(styleClasses);
        key.setCursor(Cursor.HAND);
        return key;
    }
}
