package com.example.nuriaassistant;

import com.example.nuriaassistant.config.ConfigLoader;
import com.example.nuriaassistant.models.SpotifyTrackData;
import com.example.nuriaassistant.services.NotificationServer;
import com.example.nuriaassistant.services.WeatherService;
import com.example.nuriaassistant.spotify.SpotifyService;
import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.SVGPath;
import javafx.util.Duration;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Controller for the smart assistant application.
 * Manages clock, weather, notification server, and full-screen Spotify
 * playback mode with animated Spotify logo transition when active.
 */
public class AssistantController {

    @FXML
    private AnchorPane homeLayer;

    @FXML
    private Label timeLabel;

    @FXML
    private Label weatherLabel;

    @FXML
    private Label notificationLabel;

    // Spotify Full Screen UI
    @FXML
    private AnchorPane spotifyFullScreenLayer;

    @FXML
    private Label spotifySpeakerLabel;

    @FXML
    private Label spotifyStatusLabel;

    @FXML
    private Label spotifySongNameLabel;

    @FXML
    private Label spotifyArtistNameLabel;

    @FXML
    private Label spotifyAlbumNameLabel;

    @FXML
    private ImageView spotifyCoverArtView;

    // Spotify Intro Animation UI
    @FXML
    private StackPane spotifyIntroLayer;

    @FXML
    private SVGPath spotifyIntroLogo;

    private WeatherService weatherService;
    private NotificationServer notificationServer;
    private SpotifyService spotifyService;

    // Playback and UI state
    private boolean isCurrentlyShowingSpotify = false;
    private String currentCoverUrl = null;
    private Animation activeTransition = null;

    // Background thread executor for non-blocking Spotify network polling
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

            // Generate authorization URL
            try {
                String authUrl = spotifyService.getAuthorizationUri();
                System.out.println("Spotify Auth URL: " + authUrl);
            } catch (Exception e) {
                System.err.println("Failed to generate Spotify Auth URL: " + e.getMessage());
            }

            // Start polling for currently playing track
            pollCurrentlyPlaying();
            Timeline spotifyTimer = new Timeline(new KeyFrame(Duration.seconds(4), event -> pollCurrentlyPlaying()));
            spotifyTimer.setCycleCount(Animation.INDEFINITE);
            spotifyTimer.play();
        }
    }

    /**
     * Polls Spotify for current playback state on a background thread.
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
     * Handles Spotify UI updates: shows full-screen mode with logo transition when playing,
     * hides Spotify view when paused/stopped.
     *
     * @param track Current track data.
     */
    private void updateSpotifyUi(SpotifyTrackData track) {
        boolean isPlaying = track != null && track.isPlaying() && !track.title().isBlank();

        if (isPlaying) {
            populateTrackData(track);

            if (!isCurrentlyShowingSpotify) {
                // Transition from paused to playing: play Spotify logo intro transition
                showSpotifyWithTransition();
            }
        } else {
            if (isCurrentlyShowingSpotify) {
                // Transition from playing to paused: hide Spotify and return to clock
                hideSpotifyMode();
            }
        }
    }

    /**
     * Populates track labels and loads album artwork into the full screen view.
     */
    private void populateTrackData(SpotifyTrackData track) {
        spotifySongNameLabel.setText(track.title());
        spotifyArtistNameLabel.setText(track.artist());
        spotifyAlbumNameLabel.setText(track.album() != null && !track.album().isBlank() ? track.album() : "");
        spotifySpeakerLabel.setText(track.deviceName() != null ? track.deviceName() : "Raspberry Pi Speaker");

        String coverUrl = track.coverUrl();
        if (coverUrl != null && !coverUrl.isEmpty()) {
            if (!coverUrl.equals(currentCoverUrl)) {
                currentCoverUrl = coverUrl;
                // Asynchronous background image loading with target dimensions to save RAM on Raspberry Pi 3
                Image coverImage = new Image(coverUrl, 320, 320, true, true, true);
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
     * Triggers the Spotify logo animation transition and reveals the full-screen Spotify view.
     */
    private void showSpotifyWithTransition() {
        isCurrentlyShowingSpotify = true;

        if (activeTransition != null) {
            activeTransition.stop();
        }

        // Prepare intro layer
        spotifyIntroLayer.setOpacity(0.0);
        spotifyIntroLayer.setVisible(true);
        spotifyIntroLogo.setScaleX(1.0);
        spotifyIntroLogo.setScaleY(1.0);

        // Logo scale animation (smooth pop-in)
        ScaleTransition logoScale = new ScaleTransition(Duration.millis(600), spotifyIntroLogo);
        logoScale.setFromX(1.0);
        logoScale.setFromY(1.0);
        logoScale.setToX(6.0);
        logoScale.setToY(6.0);
        logoScale.setInterpolator(Interpolator.EASE_OUT);

        // Intro layer fade in
        FadeTransition introFadeIn = new FadeTransition(Duration.millis(400), spotifyIntroLayer);
        introFadeIn.setFromValue(0.0);
        introFadeIn.setToValue(1.0);

        ParallelTransition introIn = new ParallelTransition(logoScale, introFadeIn);

        // Short pause to admire the Spotify logo
        PauseTransition pause = new PauseTransition(Duration.millis(500));

        // Intro layer fade out
        FadeTransition introFadeOut = new FadeTransition(Duration.millis(400), spotifyIntroLayer);
        introFadeOut.setFromValue(1.0);
        introFadeOut.setToValue(0.0);

        // Full screen layer fade in
        spotifyFullScreenLayer.setOpacity(0.0);
        spotifyFullScreenLayer.setVisible(true);
        FadeTransition fullScreenFadeIn = new FadeTransition(Duration.millis(400), spotifyFullScreenLayer);
        fullScreenFadeIn.setFromValue(0.0);
        fullScreenFadeIn.setToValue(1.0);

        ParallelTransition crossFade = new ParallelTransition(introFadeOut, fullScreenFadeIn);

        SequentialTransition sequence = new SequentialTransition(introIn, pause, crossFade);
        sequence.setOnFinished(event -> {
            spotifyIntroLayer.setVisible(false);
            activeTransition = null;
        });

        activeTransition = sequence;
        sequence.play();
    }

    /**
     * Smoothly fades out the full screen Spotify mode and returns to the home clock/weather screen.
     */
    private void hideSpotifyMode() {
        isCurrentlyShowingSpotify = false;

        if (activeTransition != null) {
            activeTransition.stop();
        }

        spotifyIntroLayer.setVisible(false);

        FadeTransition fadeOut = new FadeTransition(Duration.millis(500), spotifyFullScreenLayer);
        fadeOut.setFromValue(spotifyFullScreenLayer.getOpacity());
        fadeOut.setToValue(0.0);
        fadeOut.setOnFinished(event -> {
            spotifyFullScreenLayer.setVisible(false);
            spotifyCoverArtView.setImage(null);
            currentCoverUrl = null;
            activeTransition = null;
        });

        activeTransition = fadeOut;
        fadeOut.play();
    }

    /**
     * Handles Spotify OAuth callback with authorization code.
     *
     * @param authorizationCode OAuth authorization code.
     */
    @FXML
    public void handleSpotifyCallback(String authorizationCode) {
        if (spotifyService != null) {
            spotifyExecutor.submit(() -> {
                boolean success = spotifyService.exchangeCodeForTokens(authorizationCode);
                if (success) {
                    System.out.println("Spotify successfully authorized.");
                    pollCurrentlyPlaying();
                } else {
                    System.err.println("Spotify authorization failed.");
                }
            });
        }
    }

    /**
     * Cleanly shuts down background threads and server resources.
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