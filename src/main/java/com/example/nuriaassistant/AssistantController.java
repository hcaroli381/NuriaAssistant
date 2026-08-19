package com.example.nuriaassistant;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.util.Duration;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class AssistantController {
    @FXML
    private Label timeLabel;

    @FXML
    private Label weatherLabel;

    @FXML
    public void initialize() {
        // Initialize clock
        updateTime();
        Timeline clock = new Timeline(new KeyFrame(Duration.seconds(1), event -> updateTime()));
        clock.setCycleCount(Animation.INDEFINITE);
        clock.play();

        // Placeholder for weather
        weatherLabel.setText("Weather: Loading...");
    }

    private void updateTime() {
        LocalDateTime now = LocalDateTime.now();
        timeLabel.setText(now.format(DateTimeFormatter.ofPattern("HH:mm:ss")));
    }
}
