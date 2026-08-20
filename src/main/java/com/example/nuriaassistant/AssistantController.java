package com.example.nuriaassistant;

import com.example.nuriaassistant.config.ConfigLoader;
import com.example.nuriaassistant.models.SpotifyTrackData;
import com.example.nuriaassistant.services.NotificationServer;
import com.example.nuriaassistant.services.WeatherService;
import com.example.nuriaassistant.spotify.SpotifyService;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.util.Duration;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Controller for the main smart assistant view, coordinating clock, weather,
 * notification server, and Spotify track metadata / cover art display.
 */
public class AssistantController {

    @FXML
    private Label timeLabel;

    @FXML
    private Label weatherLabel;

    @FXML
    private Label notificationLabel;

    @FXML
    private Label spotifyStatusLabel;

    @FXML
    private Label spotifySongNameLabel;

    @FXML
    private Label spotifyArtistNameLabel;

    @FXML
    private ImageView spotifyCoverArtView;

    @FXML
    private Label spotifyProgressLabel;

    private WeatherService weatherService;
    private NotificationServer notificationServer;
    private SpotifyService spotifyService;

    // Track last loaded cover URL to avoid redundant image downloads and allocations
    private String currentCoverUrl = null;

    // Background thread executor for non-blocking Spotify network calls
    private final ExecutorService spotifyExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "spotify-poll-thread");
        t.setDaemon(true);
        return t;
    });

    @FXML
    public void initialize() {
        ConfigLoader configLoader = new ConfigLoader();

        // Initialize clock
        updateTime();
        Timeline clock = new Timeline(new KeyFrame(Duration.seconds(1), event -> updateTime()));
        clock.setCycleCount(Animation.INDEFINITE);
        clock.play();

        // Initialize weather service
        String apiKey = configLoader.getProperty("OPENWEATHER_API_KEY");
        String city = configLoader.getProperty("OPENWEATHER_CITY");

        if (apiKey != null && !apiKey.isEmpty()) {
            weatherService = new WeatherService(apiKey, city != null ? city : "Madrid");
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
        } catch (Exception e) {
            System.err.println("Failed to start notification server: " + e.getMessage());
        }

        // Initialize Spotify service
        String clientId = configLoader.getProperty("SPOTIFY_CLIENT_ID");
        String clientSecret = configLoader.getProperty("SPOTIFY_CLIENT_SECRET");
        String redirectUri = configLoader.getProperty("SPOTIFY_REDIRECT_URI");

        if (clientId != null && clientSecret != null && redirectUri != null) {
            spotifyService = new SpotifyService(clientId, clientSecret, redirectUri);
            System.out.println("Spotify service initialized.");

            // Start embedded OAuth callback server on port 8888
            spotifyService.startAuthCallbackServer(this::handleSpotifyCallback, 8888);

            // Generate authorization URL and display status
            try {
                String authUrl = spotifyService.getAuthorizationUri();
                System.out.println("Spotify Auth URL: " + authUrl);
                spotifyStatusLabel.setText("Spotify: Not authorized");
            } catch (Exception e) {
                System.err.println("Failed to generate Spotify Auth URL: " + e.getMessage());
                spotifyStatusLabel.setText("Spotify: Auth error");
            }

            // Start polling for currently playing track (in background thread)
            pollCurrentlyPlaying();
            Timeline spotifyTimer = new Timeline(new KeyFrame(Duration.seconds(5), event -> pollCurrentlyPlaying()));
            spotifyTimer.setCycleCount(Animation.INDEFINITE);
            spotifyTimer.play();
        } else {
            spotifyStatusLabel.setText("Spotify: Config missing");
        }
    }

    /**
     * Polls Spotify for currently playing track on background thread.
     */
    private void pollCurrentlyPlaying() {
        if (spotifyService == null || spotifyService.getAccessToken() == null) {
            return;
        }

        spotifyExecutor.submit(() -> {
            SpotifyTrackData trackData = spotifyService.getCurrentTrackData();
            Platform.runLater(() -> updateSpotifyUi(trackData));
        });
    }

    /**
     * Updates the Spotify UI components with song name, artist name, cover art, and progress.
     *
     * @param track Currently playing track metadata.
     */
    private void updateSpotifyUi(SpotifyTrackData track) {
        if (track == null || !track.isPlaying()) {
            spotifySongNameLabel.setText("No track playing");
            spotifyArtistNameLabel.setText("");
            spotifyStatusLabel.setText("Spotify: Paused");
            spotifyProgressLabel.setText("");
            if (currentCoverUrl != null) {
                spotifyCoverArtView.setImage(null);
                currentCoverUrl = null;
            }
            return;
        }

        spotifySongNameLabel.setText(track.title());
        spotifyArtistNameLabel.setText(track.artist());
        spotifyStatusLabel.setText("Spotify: Now Playing");
        spotifyProgressLabel.setText(track.getFormattedProgress());

        // Update cover art image with memory-efficient sizing for Raspberry Pi 3
        String coverUrl = track.coverUrl();
        if (coverUrl != null && !coverUrl.isEmpty()) {
            if (!coverUrl.equals(currentCoverUrl)) {
                currentCoverUrl = coverUrl;
                Image coverImage = new Image(coverUrl, 100, 100, true, true, true);
                spotifyCoverArtView.setImage(coverImage);
            }
        } else {
            if (currentCoverUrl != null) {
                spotifyCoverArtView.setImage(null);
                currentCoverUrl = null;
            }
        }
    }

    /**
     * Handles Spotify OAuth callback with the authorization code.
     *
     * @param authorizationCode OAuth authorization code.
     */
    @FXML
    public void handleSpotifyCallback(String authorizationCode) {
        if (spotifyService != null) {
            spotifyExecutor.submit(() -> {
                boolean success = spotifyService.exchangeCodeForTokens(authorizationCode);
                Platform.runLater(() -> {
                    if (success) {
                        spotifyStatusLabel.setText("Spotify: Authorized");
                        pollCurrentlyPlaying();
                    } else {
                        spotifyStatusLabel.setText("Spotify: Auth failed");
                    }
                });
            });
        }
    }

    /**
     * Releases background threads and server resources.
     */
    public void shutdown() {
        if (spotifyService != null) {
            spotifyService.stopAuthCallbackServer();
        }
        if (notificationServer != null) {
            notificationServer.stop();
        }
        spotifyExecutor.shutdownNow();
    }

    private void updateTime() {
        LocalDateTime now = LocalDateTime.now();
        timeLabel.setText(now.format(DateTimeFormatter.ofPattern("HH:mm:ss")));
    }

    private void fetchWeather() {
        if (weatherService == null) {
            return;
        }
        weatherService.fetchWeather(
                data -> {
                    if (data.temperature() == 0.0 && "Unknown".equals(data.description())) {
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