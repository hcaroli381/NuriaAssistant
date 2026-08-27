package com.example.nuriaassistant.ui;

import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.util.Duration;

/**
 * Alpha's speech bubble: the glassmorphic card with the pulsing avatar that
 * "says" remote messages (Telegram / local HTTP API). Owns the slide-up
 * entrance, the replace-pop for follow-up messages, the fade-out dismissal
 * and the reusable avatar-glow timeline — all opacity/transform-only so the
 * cached banner composite stays cheap on the Pi.
 *
 * <p>All methods must be called on the JavaFX thread (callers wrap with
 * {@code Platform.runLater} as needed).
 */
public final class NotificationBubbleUi {

    private final HBox banner;
    private final Label label;
    private final Region avatarGlow;

    private Timeline slideIn = null;
    private FadeTransition fadeOut = null;
    private Timeline glowPulse = null;

    public NotificationBubbleUi(HBox banner, Label label, Region avatarGlow) {
        this.banner = banner;
        this.label = label;
        this.avatarGlow = avatarGlow;
    }

    /** Shows a message (null/blank hides the bubble instantly). */
    public void show(String message) {
        if (message == null || message.trim().isEmpty()) {
            hide(true);
            return;
        }
        if (label != null) {
            label.setText(message.trim());
        }
        if (banner == null) {
            return;
        }

        boolean alreadyVisible = banner.isVisible();
        if (fadeOut != null) {
            fadeOut.stop();
            fadeOut = null;
        }

        if (!alreadyVisible) {
            if (slideIn != null) {
                slideIn.stop();
            }
            banner.setTranslateY(52.0);
            banner.setOpacity(0.0);
            banner.setVisible(true);
            startGlowPulse();

            slideIn = new Timeline(new KeyFrame(Duration.millis(340),
                    new KeyValue(banner.opacityProperty(), 1.0, Interpolator.EASE_OUT),
                    new KeyValue(banner.translateYProperty(), 0.0, Interpolator.EASE_OUT)));
            slideIn.setOnFinished(e -> slideIn = null);
            slideIn.play();
        } else {
            // New message replaces the old one with a gentle re-pop.
            if (slideIn != null) {
                slideIn.stop();
            }
            slideIn = new Timeline(
                    new KeyFrame(Duration.millis(90),
                            new KeyValue(banner.scaleXProperty(), 0.97, Interpolator.EASE_BOTH),
                            new KeyValue(banner.scaleYProperty(), 0.97, Interpolator.EASE_BOTH)),
                    new KeyFrame(Duration.millis(200),
                            new KeyValue(banner.scaleXProperty(), 1.0, Interpolator.EASE_OUT),
                            new KeyValue(banner.scaleYProperty(), 1.0, Interpolator.EASE_OUT)));
            slideIn.setOnFinished(e -> slideIn = null);
            slideIn.play();
        }
    }

    /** Dismisses the bubble when tapped by the user on the touchscreen. */
    public void dismiss() {
        hide(false);
    }

    /** Hides the bubble, instantly or with a short fade-down. */
    public void hide(boolean instant) {
        stopGlowPulse();
        if (slideIn != null) {
            slideIn.stop();
            slideIn = null;
        }
        if (banner == null || !banner.isVisible()) {
            return;
        }
        if (instant) {
            banner.setVisible(false);
            banner.setOpacity(0.0);
            banner.setTranslateY(0.0);
            banner.setScaleX(1.0);
            banner.setScaleY(1.0);
            if (label != null) {
                label.setText("");
            }
            return;
        }
        if (fadeOut != null) {
            fadeOut.stop();
        }
        fadeOut = new FadeTransition(Duration.millis(220), banner);
        fadeOut.setFromValue(banner.getOpacity());
        fadeOut.setToValue(0.0);
        fadeOut.setOnFinished(e -> {
            fadeOut = null;
            banner.setVisible(false);
            banner.setTranslateY(0.0);
            banner.setScaleX(1.0);
            banner.setScaleY(1.0);
            if (label != null) {
                label.setText("");
            }
        });
        fadeOut.play();
    }

    /** Stops all animations (app shutdown). */
    public void stopAnimations() {
        stopGlowPulse();
        if (slideIn != null) {
            slideIn.stop();
            slideIn = null;
        }
        if (fadeOut != null) {
            fadeOut.stop();
            fadeOut = null;
        }
    }

    private void startGlowPulse() {
        if (avatarGlow == null) {
            return;
        }
        if (glowPulse == null) {
            glowPulse = new Timeline(
                    new KeyFrame(Duration.ZERO,
                            new KeyValue(avatarGlow.opacityProperty(), 0.35, Interpolator.EASE_BOTH)),
                    new KeyFrame(Duration.millis(1100),
                            new KeyValue(avatarGlow.opacityProperty(), 0.9, Interpolator.EASE_BOTH)));
            glowPulse.setAutoReverse(true);
            glowPulse.setCycleCount(Animation.INDEFINITE);
        }
        avatarGlow.setVisible(true);
        glowPulse.playFrom(Duration.ZERO);
    }

    private void stopGlowPulse() {
        if (glowPulse != null && glowPulse.getStatus() == Animation.Status.RUNNING) {
            glowPulse.stop();
        }
        if (avatarGlow != null) {
            avatarGlow.setVisible(false);
        }
    }
}
