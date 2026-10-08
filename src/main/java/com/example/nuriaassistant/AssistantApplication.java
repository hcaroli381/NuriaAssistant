package com.example.nuriaassistant;

import com.example.nuriaassistant.config.ConfigLoader;
import com.example.nuriaassistant.ui.Cursors;
import com.example.nuriaassistant.ui.Fonts;
import com.example.nuriaassistant.util.Log;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.io.IOException;

public class AssistantApplication extends Application {

    /** Size of the Pi touchscreen; also the window size during development. */
    private static final int SCREEN_WIDTH = 1024;
    private static final int SCREEN_HEIGHT = 600;

    private AssistantController controller;

    @Override
    public void start(Stage stage) throws IOException {
        // Boot-time diagnostic: on a Pi 3 the time between "java -jar" and the
        // first painted frame is the number to watch (see PIAGENT.md, the CDS
        // archive is the knob that moves it), so it is printed once at startup.
        long startMillis = System.currentTimeMillis();

        // Alpha's own typeface must be registered before the scene resolves
        // styles.css, so every label picks it up on the first layout pass.
        Fonts.load();

        // Kiosk mode: the Pi is an always-on appliance, so the window must come
        // up by itself with no title bar and no window buttons — a minimize
        // button on a wall device reads as a bug. Off unless explicitly asked
        // for (`./mvnw javafx:run` on a dev machine stays a normal window);
        // deploy/nuria-assistant.service sets KIOSK_MODE=true on the Pi.
        ConfigLoader config = new ConfigLoader();
        boolean kiosk = Boolean.parseBoolean(config.getProperty("KIOSK_MODE"));
        Cursors.setHidden(kiosk);

        FXMLLoader fxmlLoader = new FXMLLoader(AssistantApplication.class.getResource("hello-view.fxml"));
        Parent root = fxmlLoader.load();

        // In kiosk mode the scene is sized to the whole screen (physical bounds,
        // not the visual ones: on the Pi the desktop panel lives in that strip
        // and the kiosk is meant to cover it).
        Rectangle2D screenBounds = kiosk ? Screen.getPrimary().getBounds() : null;
        Scene scene = new Scene(root,
                kiosk ? screenBounds.getWidth() : SCREEN_WIDTH,
                kiosk ? screenBounds.getHeight() : SCREEN_HEIGHT);
        scene.setFill(Color.web("#0a192f"));
        if (kiosk) {
            // Touch-only device: the arrow is noise.
            scene.setCursor(Cursor.NONE);
        }

        // Explicitly attach stylesheet to Scene
        String stylesheet = AssistantApplication.class.getResource("styles.css") != null
                ? AssistantApplication.class.getResource("styles.css").toExternalForm()
                : null;
        if (stylesheet != null) {
            scene.getStylesheets().add(stylesheet);
        }

        if (kiosk) {
            // Touch-only device: the arrow is noise. Enforce cursor: none across all elements
            scene.setCursor(Cursor.NONE);
            var kioskSheet = AssistantApplication.class.getResource("kiosk.css");
            if (kioskSheet != null) {
                scene.getStylesheets().add(kioskSheet.toExternalForm());
            }
        }

        controller = fxmlLoader.getController();

        stage.setTitle("Alpha Assistant");
        stage.setScene(scene);

        if (kiosk) {
            // Undecorated + pinned to the screen bounds is the most reliable
            // kiosk on Raspberry Pi OS (XWayland, software rendering): unlike
            // Stage#setFullScreen it cannot leave a window manager decoration
            // behind, and Screens are only asked for their bounds at startup.
            stage.initStyle(StageStyle.UNDECORATED);
            stage.setResizable(false);
            stage.setX(screenBounds.getMinX());
            stage.setY(screenBounds.getMinY());
            stage.setWidth(screenBounds.getWidth());
            stage.setHeight(screenBounds.getHeight());
            stage.setAlwaysOnTop(true);
        }

        stage.show();

        Log.info("App", "Alpha UI up in " + (System.currentTimeMillis() - startMillis)
                + " ms (kiosk=" + kiosk + ")");
    }

    @Override
    public void stop() throws Exception {
        if (controller != null) {
            controller.shutdown();
        }
        super.stop();
    }
}
