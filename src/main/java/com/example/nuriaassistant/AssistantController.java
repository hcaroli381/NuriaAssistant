package com.example.nuriaassistant;

import com.example.nuriaassistant.config.ConfigLoader;
import com.example.nuriaassistant.models.Alarm;
import com.example.nuriaassistant.models.SpotifyTrackData;
import com.example.nuriaassistant.models.VoiceAssistantSnapshot;
import com.example.nuriaassistant.services.AlarmService;
import com.example.nuriaassistant.services.NotificationServer;
import com.example.nuriaassistant.services.TelegramService;
import com.example.nuriaassistant.services.ThemeManager;
import com.example.nuriaassistant.services.VoiceAssistantService;
import com.example.nuriaassistant.services.WeatherService;
import com.example.nuriaassistant.spotify.SpotifyService;
import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.Cursor;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseEvent;
import javafx.geometry.Pos;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.util.Duration;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Main Controller for the Alpha Assistant application.
 * Manages:
 * 1. Real-time Clock and Date display (Day of week, Day of month, and Month name).
 * 2. High-contrast, minimalist top-left layout with deep Navy Blue background.
 * 3. Weather widget data fetching and presentation.
 * 4. Local Notification server with visual banner alerts.
 * 5. Full-screen Spotify playback mode with smooth intro logo animations.
 */
public class AssistantController {

    @FXML
    private AnchorPane rootPane;

    @FXML
    private AnchorPane homeLayer;

    @FXML
    private Label timeLabel;

    @FXML
    private Label dateLabel;

    @FXML
    private Label weatherCityLabel;

    @FXML
    private Label weatherIconLabel;

    @FXML
    private Label weatherTempLabel;

    @FXML
    private Label weatherDescLabel;

    @FXML
    private HBox notificationBanner;

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

    // Voice Assistant Overlay UI
    @FXML
    private AnchorPane voiceOverlayLayer;

    @FXML
    private HBox voiceOrb;

    @FXML
    private SVGPath voiceMicIcon;

    @FXML
    private SVGPath voiceMicOffIcon;

    @FXML
    private VBox voiceCard;

    @FXML
    private SVGPath voiceCardMicIcon;

    @FXML
    private Label voiceStatusLabel;

    @FXML
    private HBox voiceDotsRow;

    @FXML
    private Label voiceDot1;

    @FXML
    private Label voiceDot2;

    @FXML
    private Label voiceDot3;

    @FXML
    private VBox voiceTranscriptBubble;

    @FXML
    private Label voiceTranscriptLabel;

    @FXML
    private VBox voiceReplyBubble;

    @FXML
    private Label voiceReplyLabel;

    // Next-Alarm Hint UI
    @FXML
    private HBox nextAlarmRow;

    @FXML
    private Label nextAlarmLabel;

    // Alarm Manager Sheet UI
    @FXML
    private AnchorPane alarmManagerLayer;

    @FXML
    private VBox alarmSheetCard;

    @FXML
    private ScrollPane alarmListScroll;

    @FXML
    private VBox alarmListContainer;

    @FXML
    private Label alarmEmptyHint;

    @FXML
    private Label addAlarmButton;

    @FXML
    private VBox alarmEditorPane;

    @FXML
    private Label editorTimePreview;

    @FXML
    private Label editorHourValue;

    @FXML
    private Label editorMinuteValue;

    @FXML
    private HBox dayChipsRow;

    @FXML
    private VBox dayChipsSection;

    @FXML
    private Label editorDaysHint;

    @FXML
    private Label modeOnceButton;

    @FXML
    private Label modeRepeatButton;

    // Alarm Ring Overlay UI
    @FXML
    private AnchorPane alarmRingLayer;

    @FXML
    private AnchorPane alarmGlowPane;

    @FXML
    private Label alarmRingTimeLabel;

    @FXML
    private Label alarmRingTitleLabel;

    private WeatherService weatherService;
    private NotificationServer notificationServer;
    private TelegramService telegramService;
    private SpotifyService spotifyService;
    private VoiceAssistantService voiceService;
    private AlarmService alarmService;

    // UI State
    private boolean isCurrentlyShowingSpotify = false;
    private String currentCoverUrl = null;
    private Animation activeTransition = null;

    // Voice UI State
    private String voiceUiState = "OFFLINE";
    private boolean voiceBackendOnline = false;
    private boolean voiceMuted = false;
    private long lastVoicePollAttemptMs = 0L;
    private long lastVoiceAutoStartMs = 0L;
    private Timeline orbBreathing = null;
    private Timeline thinkingDotsAnimation = null;
    private PauseTransition replyLingerTimer = null;
    private Timeline cardSlideIn = null;
    private FadeTransition cardFadeOut = null;

    // Alarm State
    private Alarm activeRingingAlarm = null;
    private MediaPlayer alarmPlayer = null;
    private Timeline alarmGlowPulse = null;
    private int lastAlarmHintMinute = -1;

    // Alarm Editor State (null id = creating a new alarm)
    private Alarm editingAlarm = null;
    private int editorHour = 7;
    private int editorMinute = 0;
    private boolean editorOnce = false;
    private final Set<DayOfWeek> editorDays = new LinkedHashSet<>();
    private final List<Label> dayChips = new ArrayList<>();

    // Background thread executor for non-blocking Spotify polling
    private final ExecutorService spotifyExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "spotify-poll-thread");
        t.setDaemon(true);
        return t;
    });

    // Background thread executor for non-blocking voice backend polling
    private final ExecutorService voiceExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "voice-poll-thread");
        t.setDaemon(true);
        return t;
    });

    @FXML
    public void initialize() {
        ConfigLoader configLoader = new ConfigLoader();

        // 1. Initialize Clock and Date updates (1-second tick)
        updateTime();
        Timeline clockTimeline = new Timeline(new KeyFrame(Duration.seconds(1), event -> updateTime()));
        clockTimeline.setCycleCount(Animation.INDEFINITE);
        clockTimeline.play();

        // 1b. Initialize Alarm System (persistence at ~/.alpha/alarms.json)
        alarmService = new AlarmService();
        buildDayChips();
        refreshNextAlarmHint();

        // 2. Initialize Weather Service
        String apiKey = configLoader.getProperty("OPENWEATHER_API_KEY");
        String city = configLoader.getProperty("OPENWEATHER_CITY");
        String displayCity = city != null && !city.isBlank() ? city.split(",")[0].trim() : "Granada";

        if (weatherCityLabel != null) {
            weatherCityLabel.setText(displayCity);
        }

        if (apiKey != null && !apiKey.isEmpty()) {
            weatherService = new WeatherService(apiKey, city != null ? city : "Granada,ES");
            fetchWeather();
            // Refresh weather every 30 minutes
            Timeline weatherTimer = new Timeline(new KeyFrame(Duration.minutes(30), event -> fetchWeather()));
            weatherTimer.setCycleCount(Animation.INDEFINITE);
            weatherTimer.play();
        } else {
            if (weatherTempLabel != null) {
                weatherTempLabel.setText("--°C");
            }
            if (weatherDescLabel != null) {
                weatherDescLabel.setText("API key missing");
            }
        }

        // 3. Initialize Local Notification Server
        notificationServer = new NotificationServer(this::displayNotification);
        try {
            notificationServer.start();
        } catch (Exception e) {
            System.err.println("Failed to start notification server: " + e.getMessage());
        }

        // 4. Initialize Spotify Service
        String clientId = configLoader.getProperty("SPOTIFY_CLIENT_ID");
        String clientSecret = configLoader.getProperty("SPOTIFY_CLIENT_SECRET");
        String redirectUri = configLoader.getProperty("SPOTIFY_REDIRECT_URI");

        if (clientId != null && clientSecret != null && redirectUri != null) {
            spotifyService = new SpotifyService(clientId, clientSecret, redirectUri);
            System.out.println("Spotify service initialized.");

            // Start embedded OAuth callback server on port 8888
            spotifyService.startAuthCallbackServer(this::handleSpotifyCallback, 8888);

            // Print authorization URI for easy setup
            try {
                String authUrl = spotifyService.getAuthorizationUri();
                System.out.println("Spotify Auth URL: " + authUrl);
            } catch (Exception e) {
                System.err.println("Failed to generate Spotify Auth URL: " + e.getMessage());
            }

            // Start periodic polling for currently playing track (every 4 seconds)
            pollCurrentlyPlaying();
            Timeline spotifyTimer = new Timeline(new KeyFrame(Duration.seconds(4), event -> pollCurrentlyPlaying()));
            spotifyTimer.setCycleCount(Animation.INDEFINITE);
            spotifyTimer.play();
        }

        // 5. Initialize Telegram Bot Service (Remote Messaging from outside local network)
        String telegramToken = configLoader.getProperty("TELEGRAM_BOT_TOKEN");
        String telegramChatId = configLoader.getProperty("TELEGRAM_ALLOWED_CHAT_ID");

        if (telegramToken != null && !telegramToken.isBlank()) {
            telegramService = new TelegramService(telegramToken, telegramChatId, this::displayNotification);
            telegramService.start();
        }

        // 6. Initialize Voice Assistant Front-End (polls Python backend on port 8090)
        voiceService = new VoiceAssistantService(configLoader.getProperty("VOICE_BACKEND_URL"));
        initVoiceAnimations();
        pollVoiceState();
        Timeline voiceTimer = new Timeline(new KeyFrame(Duration.seconds(1), event -> pollVoiceState()));
        voiceTimer.setCycleCount(Animation.INDEFINITE);
        voiceTimer.play();

    }

    /**
     * Updates the clock display and the date (day of the week, day of month, and month name).
     * Also drives the alarm checks and refreshes the next-alarm hint once per minute.
     */
    private void updateTime() {
        LocalDateTime now = LocalDateTime.now();

        if (timeLabel != null) {
            timeLabel.setText(ThemeManager.formatTime(now));
        }
        if (dateLabel != null) {
            dateLabel.setText(ThemeManager.formatDate(now));
        }

        checkAlarms(now);
        if (now.getMinute() != lastAlarmHintMinute) {
            lastAlarmHintMinute = now.getMinute();
            refreshNextAlarmHint();
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

        // Spotify takes over the screen: hide all voice UI (the assistant
        // itself keeps running and can still execute actions).
        cancelReplyLinger();
        hideVoiceCard(true);
        voiceOverlayLayer.setVisible(false);

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
            restoreVoiceOverlayAfterSpotify();
        });

        activeTransition = fadeOut;
        fadeOut.play();
    }

    /**
     * Fetches weather information from OpenWeatherMap API.
     */
    private void fetchWeather() {
        if (weatherService == null) {
            return;
        }
        weatherService.fetchWeather(
                data -> {
                    if (data.temperature() == 0.0 && "Unknown".equals(data.description())) {
                        if (weatherTempLabel != null) weatherTempLabel.setText("--°C");
                        if (weatherDescLabel != null) weatherDescLabel.setText("Data unavailable");
                    } else {
                        String icon = getIconForWeather(data.description());
                        if (weatherIconLabel != null) weatherIconLabel.setText(icon);
                        if (weatherTempLabel != null) weatherTempLabel.setText(String.format("%.1f°C", data.temperature()));
                        if (weatherDescLabel != null) weatherDescLabel.setText(capitalize(data.description()));
                    }
                },
                error -> {
                    if (weatherTempLabel != null) weatherTempLabel.setText("--°C");
                    if (weatherDescLabel != null) weatherDescLabel.setText("Connection error");
                }
        );
    }

    /**
     * Displays a persistent notification message in the notification banner.
     * The message stays visible until touched on screen or cleared via Telegram /clear.
     *
     * @param message Notification message text.
     */
    private void displayNotification(String message) {
        Platform.runLater(() -> {
            if (message == null || message.trim().isEmpty()) {
                if (notificationBanner != null) notificationBanner.setVisible(false);
                if (notificationLabel != null) notificationLabel.setText("");
                return;
            }

            if (notificationLabel != null) {
                notificationLabel.setText(message);
            }
            if (notificationBanner != null) {
                notificationBanner.setVisible(true);
            }
        });
    }

    /**
     * Dismisses the notification banner when tapped by the user on the touchscreen.
     */
    @FXML
    public void dismissNotification() {
        if (notificationBanner != null) {
            notificationBanner.setVisible(false);
        }
        if (notificationLabel != null) {
            notificationLabel.setText("");
        }
    }

    /**
     * Returns an emoji icon for the given weather description.
     */
    private String getIconForWeather(String description) {
        if (description == null) return "🌡️";
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

    /**
     * Helper to capitalize the first letter of each word in a string.
     */
    private String capitalize(String text) {
        if (text == null || text.isEmpty()) return "";
        return Character.toUpperCase(text.charAt(0)) + text.substring(1).toLowerCase();
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

    // =========================================================================
    // VOICE ASSISTANT FRONT-END (Python backend on port 8090)
    // =========================================================================

    /**
     * Builds reusable animations once so polling never allocates transitions.
     */
    private void initVoiceAnimations() {
        orbBreathing = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(voiceOrb.scaleXProperty(), 1.0, Interpolator.EASE_BOTH),
                        new KeyValue(voiceOrb.scaleYProperty(), 1.0, Interpolator.EASE_BOTH)),
                new KeyFrame(Duration.millis(900),
                        new KeyValue(voiceOrb.scaleXProperty(), 1.08, Interpolator.EASE_BOTH),
                        new KeyValue(voiceOrb.scaleYProperty(), 1.08, Interpolator.EASE_BOTH)));
        orbBreathing.setAutoReverse(true);
        orbBreathing.setCycleCount(Animation.INDEFINITE);

        thinkingDotsAnimation = new Timeline(
                new KeyFrame(Duration.millis(0),
                        new KeyValue(voiceDot1.opacityProperty(), 1.0),
                        new KeyValue(voiceDot2.opacityProperty(), 0.25),
                        new KeyValue(voiceDot3.opacityProperty(), 0.25)),
                new KeyFrame(Duration.millis(250),
                        new KeyValue(voiceDot1.opacityProperty(), 0.25),
                        new KeyValue(voiceDot2.opacityProperty(), 1.0),
                        new KeyValue(voiceDot3.opacityProperty(), 0.25)),
                new KeyFrame(Duration.millis(500),
                        new KeyValue(voiceDot1.opacityProperty(), 0.25),
                        new KeyValue(voiceDot2.opacityProperty(), 0.25),
                        new KeyValue(voiceDot3.opacityProperty(), 1.0)),
                new KeyFrame(Duration.millis(750),
                        new KeyValue(voiceDot1.opacityProperty(), 0.25),
                        new KeyValue(voiceDot2.opacityProperty(), 0.25),
                        new KeyValue(voiceDot3.opacityProperty(), 0.25)));
        thinkingDotsAnimation.setCycleCount(Animation.INDEFINITE);
    }

    /**
     * Polls the voice backend every second on a background thread.
     * When the backend is unreachable, backs off to one attempt every 5 seconds.
     */
    private void pollVoiceState() {
        if (voiceService == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!voiceBackendOnline && now - lastVoicePollAttemptMs < 5000) {
            return;
        }
        lastVoicePollAttemptMs = now;
        voiceExecutor.submit(() -> voiceService.fetchState(this::applyVoiceSnapshot));
    }

    /**
     * Applies a runtime snapshot to the voice UI (already on the FX thread).
     * Only reacts when the derived UI state actually changes, keeping idle
     * polls allocation-free.
     */
    private void applyVoiceSnapshot(VoiceAssistantSnapshot snapshot) {
        if (isCurrentlyShowingSpotify) {
            // Spotify owns the screen: keep the assistant running but hidden.
            voiceOverlayLayer.setVisible(false);
            return;
        }

        if (voiceMuted) {
            // User muted the mic: freeze the UI until unmuted.
            return;
        }

        voiceOverlayLayer.setVisible(true);

        if (!snapshot.offline()) {
            if (!voiceBackendOnline) {
                System.out.println("Voice backend reachable; polling live.");
            }
            voiceBackendOnline = true;
            maybeAutoStartRuntime(snapshot);
        } else {
            voiceBackendOnline = false;
        }

        String target = deriveUiState(snapshot);
        if (!target.equals(voiceUiState)) {
            transitionVoiceUi(target, snapshot);
        } else {
            refreshVoiceDynamicBits(snapshot);
        }
    }

    /**
     * Starts the Python runtime loop automatically when it is found stopped,
     * throttled to one attempt every 15 seconds.
     */
    private void maybeAutoStartRuntime(VoiceAssistantSnapshot snapshot) {
        long now = System.currentTimeMillis();
        if (!snapshot.running() && now - lastVoiceAutoStartMs > 15000) {
            lastVoiceAutoStartMs = now;
            System.out.println("Voice backend online but runtime stopped. Auto-starting...");
            voiceService.startRuntime();
        }
    }

    /**
     * Maps a backend snapshot onto a coarse UI state key.
     */
    private String deriveUiState(VoiceAssistantSnapshot snapshot) {
        if (snapshot.offline()) {
            return "OFFLINE";
        }
        if (snapshot.isError()) {
            return "ERROR";
        }
        if (snapshot.isListening()) {
            return "LISTENING";
        }
        if (snapshot.isProcessing()) {
            return "PROCESSING";
        }
        if (snapshot.isSpeaking()) {
            return "SPEAKING";
        }
        return "IDLE";
    }

    /**
     * Runs the visual transition between two voice UI states.
     */
    private void transitionVoiceUi(String target, VoiceAssistantSnapshot snapshot) {
        String previous = voiceUiState;
        voiceUiState = target;

        switch (target) {
            case "OFFLINE" -> {
                setVoiceTheme("#64748b", "100,116,139", 0.35);
                stopThinkingDots();
                stopOrbBreathing();
                hideVoiceCard(true);
            }
            case "IDLE" -> {
                setVoiceTheme("#38bdf8", "56,189,248", 1.0);
                stopThinkingDots();
                startOrbBreathing();
                if ("SPEAKING".equals(previous) || "ERROR".equals(previous)) {
                    scheduleReplyLinger();
                } else {
                    hideVoiceCard(true);
                }
            }
            case "LISTENING" -> {
                setVoiceTheme("#22c55e", "34,197,94", 1.0);
                stopThinkingDots();
                stopOrbBreathing();
                voiceDotsRow.setVisible(false);
                voiceTranscriptLabel.setText("");
                voiceReplyLabel.setText("");
                voiceTranscriptBubble.setVisible(false);
                voiceReplyBubble.setVisible(false);
                voiceStatusLabel.setText("Te escucho\u2026");
                showVoiceCard();
            }
            case "PROCESSING" -> {
                setVoiceTheme("#f59e0b", "245,158,11", 1.0);
                stopOrbBreathing();
                voiceReplyBubble.setVisible(false);
                String transcript = snapshot.lastTranscript();
                voiceTranscriptLabel.setText(transcript.isBlank() ? "\u2026" : transcript);
                voiceTranscriptBubble.setVisible(true);
                voiceStatusLabel.setText("Pensando\u2026");
                voiceDotsRow.setVisible(true);
                startThinkingDots();
                showVoiceCard();
            }
            case "SPEAKING" -> {
                setVoiceTheme("#a855f7", "168,85,247", 1.0);
                stopThinkingDots();
                voiceDotsRow.setVisible(false);
                voiceReplyLabel.setText(snapshot.lastReply());
                voiceReplyBubble.setVisible(true);
                voiceStatusLabel.setText("Alpha dice:");
            }
            case "ERROR" -> {
                setVoiceTheme("#ef4444", "239,68,68", 1.0);
                stopThinkingDots();
                stopOrbBreathing();
                voiceDotsRow.setVisible(false);
                voiceTranscriptBubble.setVisible(false);
                voiceReplyBubble.setVisible(false);
                String error = snapshot.lastError();
                if (error.isBlank()) {
                    error = "Error de voz";
                } else if (error.length() > 64) {
                    error = error.substring(0, 64) + "\u2026";
                }
                voiceStatusLabel.setText(error);
                showVoiceCard();
            }
            default -> { }
        }
    }

    /**
     * Applies incremental updates within an unchanged state (wake-word glow,
     * live transcript/reply refreshes).
     */
    private void refreshVoiceDynamicBits(VoiceAssistantSnapshot snapshot) {
        switch (voiceUiState) {
            case "IDLE" -> {
                double score = Math.min(1.0, snapshot.wakeWordScore() / 0.6);
                voiceOrb.setOpacity(0.78 + 0.22 * score);
            }
            case "PROCESSING" -> {
                String transcript = snapshot.lastTranscript();
                if (!transcript.isBlank() && !transcript.equals(voiceTranscriptLabel.getText())) {
                    voiceTranscriptLabel.setText(transcript);
                }
            }
            case "SPEAKING" -> {
                String reply = snapshot.lastReply();
                if (!reply.isBlank() && !reply.equals(voiceReplyLabel.getText())) {
                    voiceReplyLabel.setText(reply);
                }
            }
            default -> { }
        }
    }

    /**
     * Colors the mic icons, status label and orb border for the active state,
     * mirroring the LED ring palette of the backend.
     */
    private void setVoiceTheme(String hex, String rgb, double orbOpacity) {
        Color color = Color.web(hex);
        voiceMicIcon.setFill(color);
        voiceCardMicIcon.setFill(color);
        voiceStatusLabel.setStyle("-fx-text-fill: " + hex + ";");
        voiceOrb.setStyle("-fx-background-color: rgba(" + rgb + ", 0.16);"
                + " -fx-border-color: rgba(" + rgb + ", 0.55);");
        voiceOrb.setOpacity(orbOpacity);
    }

    /**
     * Toggles the mic on touch: mutes (stops the backend runtime) or
     * unmutes (starts it again). Auto-start stays suppressed while muted.
     */
    @FXML
    public void toggleVoiceMute() {
        voiceMuted = !voiceMuted;
        if (voiceService == null) {
            return;
        }
        if (voiceMuted) {
            voiceService.stopRuntime();
            enterMutedUi();
            System.out.println("Voice assistant muted by touch.");
        } else {
            voiceService.startRuntime();
            exitMutedUi();
            System.out.println("Voice assistant unmuted by touch.");
        }
    }

    /**
     * Brings the voice UI back once the Spotify full-screen mode fades out.
     */
    private void restoreVoiceOverlayAfterSpotify() {
        if (voiceMuted) {
            voiceOverlayLayer.setVisible(true);
            return;
        }
        voiceOverlayLayer.setVisible(true);
        // Force a full state transition so visuals re-sync with the backend.
        voiceUiState = "";
        pollVoiceState();
    }

    private void enterMutedUi() {
        cancelReplyLinger();
        stopThinkingDots();
        stopOrbBreathing();
        hideVoiceCard(true);

        voiceMicIcon.setVisible(false);
        voiceMicOffIcon.setVisible(true);
        voiceMicOffIcon.setFill(Color.web("#ef4444"));

        if (!voiceOrb.getStyleClass().contains("voice-orb-muted")) {
            voiceOrb.getStyleClass().add("voice-orb-muted");
        }
        voiceOrb.setStyle(null);
        voiceOrb.setOpacity(1.0);
    }

    private void exitMutedUi() {
        voiceOrb.getStyleClass().remove("voice-orb-muted");
        voiceMicOffIcon.setVisible(false);
        voiceMicIcon.setVisible(true);

        setVoiceTheme("#38bdf8", "56,189,248", 1.0);
        startOrbBreathing();

        // Force the next snapshot to run a full state transition.
        voiceUiState = "";
    }

    /**
     * Reveals the conversation card with a short slide-up + fade-in.
     */
    private void showVoiceCard() {
        if (cardFadeOut != null) {
            cardFadeOut.stop();
            cardFadeOut = null;
        }

        if (!voiceCard.isVisible()) {
            voiceCard.setTranslateY(24.0);
            voiceCard.setOpacity(0.0);
            voiceCard.setVisible(true);

            if (cardSlideIn != null) {
                cardSlideIn.stop();
            }
            cardSlideIn = new Timeline(new KeyFrame(Duration.millis(220),
                    new KeyValue(voiceCard.opacityProperty(), 1.0, Interpolator.EASE_OUT),
                    new KeyValue(voiceCard.translateYProperty(), 0.0, Interpolator.EASE_OUT)));
            cardSlideIn.play();
        } else {
            voiceCard.setOpacity(1.0);
            voiceCard.setTranslateY(0.0);
        }
    }

    /**
     * Hides the conversation card, instantly or with a soft fade-out.
     */
    private void hideVoiceCard(boolean instant) {
        if (cardSlideIn != null) {
            cardSlideIn.stop();
            cardSlideIn = null;
        }
        cancelReplyLinger();

        if (!voiceCard.isVisible()) {
            return;
        }

        if (instant) {
            voiceCard.setVisible(false);
            voiceCard.setOpacity(0.0);
            voiceCard.setTranslateY(0.0);
            return;
        }

        if (cardFadeOut != null) {
            cardFadeOut.stop();
        }
        cardFadeOut = new FadeTransition(Duration.millis(400), voiceCard);
        cardFadeOut.setFromValue(voiceCard.getOpacity());
        cardFadeOut.setToValue(0.0);
        cardFadeOut.setOnFinished(e -> {
            voiceCard.setVisible(false);
            voiceCard.setTranslateY(0.0);
            cardFadeOut = null;
        });
        cardFadeOut.play();
    }

    /**
     * Keeps Alpha's reply on screen for 8 seconds after speaking ends.
     */
    private void scheduleReplyLinger() {
        cancelReplyLinger();
        replyLingerTimer = new PauseTransition(Duration.seconds(8));
        replyLingerTimer.setOnFinished(e -> hideVoiceCard(false));
        replyLingerTimer.play();
    }

    private void cancelReplyLinger() {
        if (replyLingerTimer != null) {
            replyLingerTimer.stop();
            replyLingerTimer.setOnFinished(null);
            replyLingerTimer = null;
        }
    }

    private void startOrbBreathing() {
        if (orbBreathing.getStatus() != Animation.Status.RUNNING) {
            orbBreathing.playFrom(Duration.ZERO);
        }
    }

    private void stopOrbBreathing() {
        if (orbBreathing.getStatus() == Animation.Status.RUNNING) {
            orbBreathing.stop();
        }
        voiceOrb.setScaleX(1.0);
        voiceOrb.setScaleY(1.0);
    }

    private void startThinkingDots() {
        if (thinkingDotsAnimation.getStatus() != Animation.Status.RUNNING) {
            thinkingDotsAnimation.playFrom(Duration.ZERO);
        }
    }

    private void stopThinkingDots() {
        if (thinkingDotsAnimation.getStatus() == Animation.Status.RUNNING) {
            thinkingDotsAnimation.stop();
        }
        voiceDot1.setOpacity(0.25);
        voiceDot2.setOpacity(0.25);
        voiceDot3.setOpacity(0.25);
    }

    // =========================================================================
    // ALARM SYSTEM (tactile: full-screen ring overlay + manager sheet)
    // =========================================================================

    /**
     * Called every second from the clock tick. Fires the first due alarm.
     */
    private void checkAlarms(LocalDateTime now) {
        if (alarmService == null || alarmRingLayer.isVisible()) {
            return;
        }
        Optional<Alarm> due = alarmService.findDueAlarm(now);
        due.ifPresent(this::startRinging);
    }

    /**
     * Shows the full-screen ringing overlay: big time, pulsing glow and looping sound.
     */
    private void startRinging(Alarm alarm) {
        activeRingingAlarm = alarm;

        alarmRingTimeLabel.setText(alarm.displayTime());
        String title = alarm.label();
        alarmRingTitleLabel.setText(title == null || title.isBlank() ? "Alarma" : title);

        // The manager sheet must never stay above the ringing overlay
        if (alarmManagerLayer.isVisible()) {
            closeAlarmManager();
        }

        ensureAlarmSound();
        if (alarmPlayer != null) {
            try {
                alarmPlayer.seek(Duration.ZERO);
                alarmPlayer.play();
            } catch (Exception e) {
                System.err.println("Alarm audio playback failed: " + e.getMessage());
            }
        }

        startGlowPulse();

        alarmRingLayer.setOpacity(0.0);
        alarmRingLayer.setVisible(true);
        FadeTransition fadeIn = new FadeTransition(Duration.millis(350), alarmRingLayer);
        fadeIn.setFromValue(0.0);
        fadeIn.setToValue(1.0);
        fadeIn.play();

        System.out.println("Alarm ringing: " + alarm.displayTime());
    }

    /** Snooze button (+5 min) on the ring overlay. */
    @FXML
    public void snoozeActiveAlarm() {
        if (activeRingingAlarm != null && alarmService != null) {
            alarmService.snooze(activeRingingAlarm, 5);
        }
        stopRinging();
    }

    /** Dismiss button on the ring overlay. One-shot alarms are removed once dismissed. */
    @FXML
    public void dismissActiveAlarm() {
        Alarm fired = activeRingingAlarm;
        stopRinging();
        if (fired != null && fired.isOnce() && alarmService != null) {
            alarmService.removeAlarm(fired.id());
            refreshNextAlarmHint();
        }
    }

    private void stopRinging() {
        activeRingingAlarm = null;
        stopGlowPulse();
        if (alarmPlayer != null) {
            try {
                alarmPlayer.stop();
            } catch (Exception ignored) {
            }
        }
        refreshNextAlarmHint();

        if (!alarmRingLayer.isVisible()) {
            return;
        }
        FadeTransition fadeOut = new FadeTransition(Duration.millis(300), alarmRingLayer);
        fadeOut.setFromValue(alarmRingLayer.getOpacity());
        fadeOut.setToValue(0.0);
        fadeOut.setOnFinished(e -> alarmRingLayer.setVisible(false));
        fadeOut.play();
    }

    /**
     * Lazily prepares the looping alarm sound player (WAV asset, no new dependencies).
     */
    private void ensureAlarmSound() {
        if (alarmPlayer != null) {
            return;
        }
        try {
            java.net.URL soundUrl = getClass().getResource("/com/example/nuriaassistant/sounds/alarm.wav");
            if (soundUrl == null) {
                System.err.println("Alarm sound resource not found.");
                return;
            }
            Media media = new Media(soundUrl.toExternalForm());
            MediaPlayer player = new MediaPlayer(media);
            player.setCycleCount(MediaPlayer.INDEFINITE);
            player.setVolume(0.9);
            alarmPlayer = player;
        } catch (Exception e) {
            System.err.println("Failed to prepare alarm audio: " + e.getMessage());
        }
    }

    private void startGlowPulse() {
        if (alarmGlowPulse == null) {
            alarmGlowPulse = new Timeline(
                    new KeyFrame(Duration.ZERO,
                            new KeyValue(alarmGlowPane.opacityProperty(), 0.25, Interpolator.EASE_BOTH)),
                    new KeyFrame(Duration.millis(850),
                            new KeyValue(alarmGlowPane.opacityProperty(), 1.0, Interpolator.EASE_BOTH)));
            alarmGlowPulse.setAutoReverse(true);
            alarmGlowPulse.setCycleCount(Animation.INDEFINITE);
        }
        alarmGlowPane.setVisible(true);
        alarmGlowPulse.playFrom(Duration.ZERO);
    }

    private void stopGlowPulse() {
        if (alarmGlowPulse != null && alarmGlowPulse.getStatus() == Animation.Status.RUNNING) {
            alarmGlowPulse.stop();
        }
        alarmGlowPane.setVisible(false);
    }

    /**
     * Updates the subtle next-alarm hint under the weather row.
     */
    private void refreshNextAlarmHint() {
        if (nextAlarmRow == null || nextAlarmLabel == null) {
            return;
        }
        String summary = alarmService != null ? alarmService.nextAlarmSummary() : null;
        nextAlarmRow.setVisible(summary != null);
        nextAlarmLabel.setText(summary != null ? summary : "");
    }

    // -------------------------------------------------------------------------
    // Alarm Manager Sheet (list / add / toggle / delete)
    // -------------------------------------------------------------------------

    @FXML
    public void openAlarmManager() {
        exitAlarmEditor();
        rebuildAlarmList();
        alarmManagerLayer.setOpacity(0.0);
        alarmManagerLayer.setVisible(true);
        FadeTransition fadeIn = new FadeTransition(Duration.millis(200), alarmManagerLayer);
        fadeIn.setFromValue(0.0);
        fadeIn.setToValue(1.0);
        fadeIn.play();
    }

    @FXML
    public void closeAlarmManager() {
        if (!alarmManagerLayer.isVisible()) {
            return;
        }
        exitAlarmEditor();
        FadeTransition fadeOut = new FadeTransition(Duration.millis(180), alarmManagerLayer);
        fadeOut.setToValue(0.0);
        fadeOut.setOnFinished(e -> alarmManagerLayer.setVisible(false));
        fadeOut.play();
    }

    /** Closes the sheet only when the backdrop itself is clicked, not its children. */
    @FXML
    public void closeAlarmManagerOnOutsideClick(MouseEvent event) {
        if (event.getTarget() == alarmManagerLayer) {
            closeAlarmManager();
        }
    }

    /** Keeps clicks inside the card from bubbling to the backdrop handler. */
    @FXML
    public void swallowAlarmCardClick(MouseEvent event) {
        event.consume();
    }

    /**
     * Rebuilds the alarm rows (sorted by time). Tapping a row opens the editor;
     * toggle and delete buttons consume their clicks so they don't trigger edits.
     */
    private void rebuildAlarmList() {
        alarmListContainer.getChildren().clear();
        List<Alarm> alarms = alarmService.getAlarms();
        alarms.sort(Comparator.comparingInt(Alarm::hour).thenComparingInt(Alarm::minute));

        boolean empty = alarms.isEmpty();
        alarmEmptyHint.setVisible(empty);
        alarmEmptyHint.setManaged(empty);

        for (Alarm alarm : alarms) {
            HBox row = new HBox(14);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("alarm-row");
            if (!alarm.isEnabled()) {
                row.getStyleClass().add("alarm-row-disabled");
            }
            row.setCursor(Cursor.HAND);
            row.setOnMouseClicked(e -> openAlarmEditorFor(alarm));

            Label timeLabel = new Label(alarm.displayTime());
            timeLabel.getStyleClass().add("alarm-row-time");

            VBox mid = new VBox(2);
            String name = alarm.label();
            String desc = name == null || name.isBlank()
                    ? alarm.repeatDescription()
                    : name + "  \u00b7  " + alarm.repeatDescription();
            Label descLabel = new Label(desc);
            descLabel.getStyleClass().add("alarm-row-desc");
            mid.getChildren().add(descLabel);

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            Label toggle = new Label(alarm.isEnabled() ? "ON" : "OFF");
            toggle.getStyleClass().add(alarm.isEnabled() ? "toggle-pill-on" : "toggle-pill-off");
            toggle.setOnMouseClicked(e -> {
                e.consume();
                alarm.setEnabled(!alarm.isEnabled());
                alarmService.updateAlarm(alarm);
                rebuildAlarmList();
                refreshNextAlarmHint();
            });

            Label deleteBtn = new Label("\u2715");
            deleteBtn.getStyleClass().add("alarm-delete-btn");
            deleteBtn.setOnMouseClicked(e -> {
                e.consume();
                alarmService.removeAlarm(alarm.id());
                rebuildAlarmList();
                refreshNextAlarmHint();
            });

            row.getChildren().addAll(timeLabel, mid, spacer, toggle, deleteBtn);
            alarmListContainer.getChildren().add(row);
        }
    }

    // -------------------------------------------------------------------------
    // Alarm Editor (touch steppers + day chips)
    // -------------------------------------------------------------------------

    @FXML
    public void showNewAlarmEditor() {
        editingAlarm = null;
        LocalDateTime now = LocalDateTime.now();
        editorHour = now.getHour();          // default: current hour
        editorMinute = now.getMinute() < 55 ? ((now.getMinute() / 5) + 1) * 5 % 60 : 55;
        editorOnce = false;                  // default: repeating
        editorDays.clear();                  // empty selection = every day
        enterAlarmEditor();
    }

    private void openAlarmEditorFor(Alarm alarm) {
        editingAlarm = alarm;
        editorHour = alarm.hour();
        editorMinute = alarm.minute();
        editorOnce = alarm.isOnce();
        editorDays.clear();
        editorDays.addAll(alarm.repeatDays());
        enterAlarmEditor();
    }

    /** Mode selector: this alarm rings once and deletes itself after dismissal. */
    @FXML
    public void selectOnceMode() {
        editorOnce = true;
        refreshEditorUi();
    }

    /** Mode selector: this alarm repeats (day chips decide the pattern). */
    @FXML
    public void selectRepeatMode() {
        editorOnce = false;
        refreshEditorUi();
    }

    private void enterAlarmEditor() {
        alarmListScroll.setVisible(false);
        alarmListScroll.setManaged(false);
        alarmEmptyHint.setVisible(false);
        alarmEmptyHint.setManaged(false);
        addAlarmButton.setVisible(false);
        addAlarmButton.setManaged(false);

        alarmEditorPane.setVisible(true);
        alarmEditorPane.setManaged(true);
        refreshEditorUi();
    }

    private void exitAlarmEditor() {
        if (alarmEditorPane == null) {
            return;
        }
        alarmEditorPane.setVisible(false);
        alarmEditorPane.setManaged(false);
        alarmListScroll.setVisible(true);
        alarmListScroll.setManaged(true);
        addAlarmButton.setVisible(true);
        addAlarmButton.setManaged(true);
        editingAlarm = null;
    }

    @FXML
    public void incrementHour() {
        editorHour = (editorHour + 1) % 24;
        refreshEditorUi();
    }

    @FXML
    public void decrementHour() {
        editorHour = (editorHour + 23) % 24;
        refreshEditorUi();
    }

    @FXML
    public void incrementMinute() {
        editorMinute = (editorMinute + 5) % 60;
        refreshEditorUi();
    }

    @FXML
    public void decrementMinute() {
        editorMinute = (editorMinute + 55) % 60;
        refreshEditorUi();
    }

    /** Builds the L M X J V S D chips once at startup (Monday-first). */
    private void buildDayChips() {
        DayOfWeek[] days = {
                DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY};
        for (DayOfWeek day : days) {
            Label chip = new Label(chipLetter(day));
            chip.getStyleClass().add("day-chip");
            chip.setUserData(day);
            chip.setCursor(Cursor.HAND);
            chip.setOnMouseClicked(e -> {
                if (editorOnce) {
                    return; // day chips are irrelevant in one-shot mode
                }
                DayOfWeek selected = (DayOfWeek) chip.getUserData();
                if (!editorDays.remove(selected)) {
                    editorDays.add(selected);
                }
                refreshEditorUi();
            });
            dayChips.add(chip);
            dayChipsRow.getChildren().add(chip);
        }
    }

    private static String chipLetter(DayOfWeek day) {
        return switch (day) {
            case MONDAY -> "L";
            case TUESDAY -> "M";
            case WEDNESDAY -> "X";
            case THURSDAY -> "J";
            case FRIDAY -> "V";
            case SATURDAY -> "S";
            case SUNDAY -> "D";
        };
    }

    private void refreshEditorUi() {
        editorTimePreview.setText(String.format(Locale.ROOT, "%02d:%02d", editorHour, editorMinute));
        editorHourValue.setText(String.format(Locale.ROOT, "%02d", editorHour));
        editorMinuteValue.setText(String.format(Locale.ROOT, "%02d", editorMinute));

        // Repeat-mode selector visuals
        styleModeButton(modeOnceButton, editorOnce);
        styleModeButton(modeRepeatButton, !editorOnce);
        dayChipsSection.setOpacity(editorOnce ? 0.35 : 1.0);

        for (Label chip : dayChips) {
            DayOfWeek selected = (DayOfWeek) chip.getUserData();
            if (!editorOnce && editorDays.contains(selected)) {
                if (!chip.getStyleClass().contains("day-chip-selected")) {
                    chip.getStyleClass().add("day-chip-selected");
                }
            } else {
                chip.getStyleClass().remove("day-chip-selected");
            }
        }

        if (editorOnce) {
            editorDaysHint.setText("Sonar\u00e1 solo la pr\u00f3xima vez y se borrar\u00e1 al apagarlo");
        } else {
            editorDaysHint.setText(editorDays.isEmpty()
                    ? "Sin selecci\u00f3n: sonar\u00e1 todos los d\u00edas"
                    : "Sonar\u00e1: " + describeDays(editorDays));
        }
    }

    private void styleModeButton(Label button, boolean selected) {
        button.getStyleClass().remove("alarm-mode-button");
        button.getStyleClass().remove("alarm-mode-button-selected");
        button.getStyleClass().add(selected ? "alarm-mode-button-selected" : "alarm-mode-button");
    }

    private static String describeDays(Set<DayOfWeek> days) {
        StringBuilder sb = new StringBuilder();
        for (DayOfWeek day : days) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(chipLetter(day));
        }
        return sb.toString();
    }

    @FXML
    public void cancelAlarmEditor() {
        exitAlarmEditor();
    }

    @FXML
    public void saveAlarmFromEditor() {
        if (editingAlarm != null) {
            editingAlarm.setHour(editorHour);
            editingAlarm.setMinute(editorMinute);
            editingAlarm.setOnce(editorOnce);
            editingAlarm.setRepeatDays(editorOnce ? new LinkedHashSet<>() : new LinkedHashSet<>(editorDays));
            alarmService.updateAlarm(editingAlarm);
        } else {
            Alarm alarm = new Alarm(editorHour, editorMinute, "",
                    editorOnce ? new LinkedHashSet<>() : new LinkedHashSet<>(editorDays), editorOnce);
            alarmService.addAlarm(alarm);
        }
        exitAlarmEditor();
        rebuildAlarmList();
        refreshNextAlarmHint();
    }

    /**
     * Cleanly shuts down background threads and server resources.
     */
    public void shutdown() {
        if (telegramService != null) {
            telegramService.stop();
        }
        if (spotifyService != null) {
            spotifyService.stopAuthCallbackServer();
        }
        if (notificationServer != null) {
            notificationServer.stop();
        }
        spotifyExecutor.shutdownNow();
        voiceExecutor.shutdownNow();
        stopOrbBreathing();
        stopThinkingDots();
        cancelReplyLinger();
        stopGlowPulse();
        if (alarmPlayer != null) {
            try {
                alarmPlayer.stop();
                alarmPlayer.dispose();
            } catch (Exception ignored) {
            }
            alarmPlayer = null;
        }
    }
}
