package com.example.nuriaassistant.ui;

import com.example.nuriaassistant.config.ConfigLoader;
import javafx.animation.FadeTransition;
import javafx.scene.layout.AnchorPane;
import javafx.util.Duration;

import java.time.LocalDateTime;

/**
 * Screen-saver dimming for the always-on display: fades a full-screen overlay
 * when the device has been idle for {@code DIM_IDLE_MINUTES} during the
 * configured night window (start &gt; end crosses midnight), and lifts it on any
 * touch, incoming notification or alarm.
 *
 * <p>The owning controller feeds it {@link #checkDimming} from its clock tick
 * and {@link #touchActivity} from its input event filters; nothing else in the
 * scene needs to know about the overlay.
 */
public final class NightDimmingController {

    private final AnchorPane dimLayer;
    private final int idleMinutes;
    private final int startHour;
    private final int endHour;

    private long lastInteractionMillis = System.currentTimeMillis();
    private boolean dimmed = false;
    private FadeTransition dimFade = null;

    public NightDimmingController(AnchorPane dimLayer, ConfigLoader configLoader) {
        this.dimLayer = dimLayer;
        this.idleMinutes = readInt(configLoader, "DIM_IDLE_MINUTES", 10, 1, 24 * 60);
        this.startHour = readInt(configLoader, "DIM_START_HOUR", 22, 0, 23);
        this.endHour = readInt(configLoader, "DIM_END_HOUR", 8, 0, 23);
    }

    /** Runs every second from the clock tick: dim / wake based on night hours + idle time. */
    public void checkDimming(LocalDateTime now) {
        boolean night = isNightHour(now.getHour());
        boolean idleLong = System.currentTimeMillis() - lastInteractionMillis >= idleMinutes * 60_000L;
        if (dimmed) {
            if (!night || !idleLong) {
                wake();
            }
        } else if (night && idleLong) {
            dim();
        }
    }

    /** Any touch on the screen: marks activity and lifts the dim when present. */
    public void touchActivity() {
        lastInteractionMillis = System.currentTimeMillis();
        if (dimmed) {
            wake();
        }
    }

    /** Lifts the dim so something on screen can be seen (notification, alarm, …). */
    public void wake() {
        if (!dimmed || dimLayer == null) {
            return;
        }
        dimmed = false;
        if (dimFade != null) {
            dimFade.stop();
        }
        dimFade = new FadeTransition(Duration.millis(500), dimLayer);
        dimFade.setFromValue(dimLayer.getOpacity());
        dimFade.setToValue(0.0);
        dimFade.setOnFinished(e -> dimLayer.setVisible(false));
        dimFade.play();
    }

    /** Stops any running fade (app shutdown). */
    public void stop() {
        if (dimFade != null) {
            dimFade.stop();
            dimFade = null;
        }
    }

    private void dim() {
        if (dimmed || dimLayer == null) {
            return;
        }
        dimmed = true;
        if (dimFade != null) {
            dimFade.stop();
        }
        dimLayer.setVisible(true);
        dimFade = new FadeTransition(Duration.millis(900), dimLayer);
        dimFade.setFromValue(0.0);
        dimFade.setToValue(1.0);
        dimFade.play();
    }

    /** True when the given hour falls inside the dim window (supports crossing midnight). */
    private boolean isNightHour(int hour) {
        if (startHour <= endHour) {
            return hour >= startHour && hour < endHour;
        }
        return hour >= startHour || hour < endHour;
    }

    private static int readInt(ConfigLoader configLoader, String key, int fallback, int min, int max) {
        String value = configLoader.getProperty(key);
        if (value == null) {
            return fallback;
        }
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(value.trim())));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
