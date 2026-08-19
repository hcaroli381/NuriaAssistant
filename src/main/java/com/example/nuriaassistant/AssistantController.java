package com.example.nuriaassistant;

import com.example.nuriaassistant.services.NotificationServer;
import com.example.nuriaassistant.services.WeatherService;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.util.Duration;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class AssistantController {
    @FXML
    private Label timeLabel;

    @FXML
    private Label weatherLabel;

    @FXML
    private Label notificationLabel;

    private WeatherService weatherService;
    private NotificationServer notificationServer;

    @FXML
    public void initialize() {
        // Initialize clock
        updateTime();
        Timeline clock = new Timeline(new KeyFrame(Duration.seconds(1), event -> updateTime()));
        clock.setCycleCount(Animation.INDEFINITE);
        clock.play();

        // Initialize weather service
        String apiKey = System.getenv("OPENWEATHER_API_KEY");
        String city = "Madrid";

        if (apiKey != null && !apiKey.isEmpty()) {
            weatherService = new WeatherService(apiKey, city);
            fetchWeather();
            Timeline weatherTimer = new Timeline(new KeyFrame(Duration.minutes(30), event -> fetchWeather()));
            weatherTimer.setCycleCount(Animation.INDEFINITE);
            weatherTimer.play();
        } else {
            weatherLabel.setText("Weather: API Key missing");
        }

        // Initialize notification server
        notificationServer = new NotificationServer(this::displayNotification);
        try {
            notificationServer.start();
        } catch (IOException e) {
            System.err.println("Failed to start notification server: " + e.getMessage());
        }
    }

    private void updateTime() {
        LocalDateTime now = LocalDateTime.now();
        timeLabel.setText(now.format(DateTimeFormatter.ofPattern("HH:mm:ss")));
    }

    private void fetchWeather() {
        weatherService.fetchWeather(
            data -> {
                if (data.temperature() == 0.0 && data.description().equals("Unknown")) {
                    weatherLabel.setText("Weather: Error parsing data");
                } else {
                    String icon = getIconForWeather(data.description());
                    weatherLabel.setText(String.format("%s %.1f°C, %s", icon, data.temperature(), data.description()));
                }
            },
            error -> weatherLabel.setText("Weather: Connection error")
        );
    }

    private void displayNotification(String message) {
        Platform.runLater(() -> {
            notificationLabel.setText(message);
            // Hide notification after 10 seconds
            Timeline hideTimer = new Timeline(new KeyFrame(Duration.seconds(10), event -> notificationLabel.setText("")));
            hideTimer.play();
        });
    }

    private String getIconForWeather(String description) {
        return switch (description.toLowerCase()) {
            case "clear" -> "☀️";
            case "clouds" -> "☁️";
            case "rain" -> "🌧️";
            case "drizzle" -> "🌦️";
            case "thunderstorm" -> "⛈️";
            case "snow" -> "❄️";
            default -> "🌡️";
        };
    }
}
