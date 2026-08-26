package com.example.nuriaassistant;

import com.example.nuriaassistant.config.ConfigLoader;
import com.example.nuriaassistant.models.Alarm;
import com.example.nuriaassistant.models.CalendarEvent;
import com.example.nuriaassistant.models.SpotifyTrackData;
import com.example.nuriaassistant.models.VoiceAssistantSnapshot;
import com.example.nuriaassistant.services.AlarmService;
import com.example.nuriaassistant.services.CalendarService;
import com.example.nuriaassistant.services.NotificationServer;
import com.example.nuriaassistant.services.PhotoFrameService;
import com.example.nuriaassistant.services.TelegramService;
import com.example.nuriaassistant.services.ThemeManager;
import com.example.nuriaassistant.services.VoiceAssistantService;
import com.example.nuriaassistant.services.VoiceBackendLauncher;
import com.example.nuriaassistant.services.WeatherService;
import com.example.nuriaassistant.spotify.SpotifyQrGenerator;
import com.example.nuriaassistant.spotify.SpotifyService;
import com.google.zxing.common.BitMatrix;
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
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.Cursor;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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

    @FXML
    private Region notificationAvatarGlow;

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

    // Next-Event Hint UI
    @FXML
    private HBox nextEventRow;

    @FXML
    private Label nextEventLabel;

    // Calendar Screen UI
    @FXML
    private AnchorPane calendarLayer;

    @FXML
    private VBox calendarCard;

    @FXML
    private Label calendarTitleLabel;

    @FXML
    private VBox calendarGrid;

    @FXML
    private Label calendarSelectedDateLabel;

    @FXML
    private VBox calendarEventList;

    @FXML
    private Label calendarBadgeLabel;

    // Photo Frame UI
    @FXML
    private HBox photoFrameButton;

    @FXML
    private AnchorPane photoLayer;

    @FXML
    private ImageView photoViewA;

    @FXML
    private ImageView photoViewB;

    @FXML
    private Label photoCounterLabel;

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

    // Spotify QR Auth Overlay UI
    @FXML
    private AnchorPane spotifyAuthLayer;

    @FXML
    private VBox spotifyAuthCard;

    @FXML
    private ImageView spotifyAuthQrView;

    @FXML
    private Label spotifyAuthStatusLabel;

    private WeatherService weatherService;
    private NotificationServer notificationServer;
    private TelegramService telegramService;
    private SpotifyService spotifyService;
    private VoiceAssistantService voiceService;
    private VoiceBackendLauncher voiceBackendLauncher;
    private AlarmService alarmService;
    private CalendarService calendarService;

    // UI State
    private boolean isCurrentlyShowingSpotify = false;
    private String currentCoverUrl = null;
    private Animation activeTransition = null;
    private String lastTrackSignature = null;

    // Voice UI State
    private String voiceUiState = "OFFLINE";
    private boolean voiceBackendOnline = false;
    private boolean voiceMuted = false;
    private long lastVoicePollAttemptMs = 0L;
    private long lastVoiceAutoStartMs = 0L;
    private long lastVoiceSpawnAttemptMs = 0L;
    private double lastVoiceOrbOpacity = -1.0;
    private Timeline orbBreathing = null;
    private Timeline thinkingDotsAnimation = null;
    private PauseTransition replyLingerTimer = null;
    private Timeline cardSlideIn = null;
    private FadeTransition cardFadeOut = null;

    // Notification Bubble State
    private Timeline notificationSlideIn = null;
    private FadeTransition notificationFadeOut = null;
    private Timeline avatarGlowPulse = null;

    // Alarm State
    private Alarm activeRingingAlarm = null;
    private MediaPlayer alarmPlayer = null;
    private Timeline alarmGlowPulse = null;
    private int lastAlarmHintMinute = -1;
    private String cachedDateText = null;

    // Alarm Editor State (null id = creating a new alarm)
    private Alarm editingAlarm = null;
    private int editorHour = 7;
    private int editorMinute = 0;
    private boolean editorOnce = false;
    private final Set<DayOfWeek> editorDays = new LinkedHashSet<>();
    private final List<Label> dayChips = new ArrayList<>();

    // Calendar Screen State
    private List<CalendarEvent> calendarEvents = List.of();
    private YearMonth displayedMonth = null;
    private LocalDate selectedCalendarDate = null;
    private final Map<LocalDate, VBox> calendarDayCells = new HashMap<>();
    private static final DateTimeFormatter CALENDAR_MONTH_FORMAT =
            DateTimeFormatter.ofPattern("MMMM yyyy", new Locale("es", "ES"));
    private static final DateTimeFormatter CALENDAR_DAY_FORMAT =
            DateTimeFormatter.ofPattern("EEEE d' de 'MMMM", new Locale("es", "ES"));

    // Photo Frame State
    private PhotoFrameService photoStore;
    private List<Path> photoLibrary = List.of();
    private int currentPhotoIndex = -1;
    private boolean photoFrontIsA = true;
    private Image preloadedPhoto = null;
    private Path preloadedPhotoPath = null;
    private PauseTransition photoSlideTimer = null;
    private FadeTransition photoCrossFade = null;

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

    // Single daemon ticker driving weather/Spotify/voice periodic work off the FX thread
    private final ScheduledExecutorService backgroundTicker = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "assistant-ticker");
        t.setDaemon(true);
        return t;
    });

    @FXML
    public void initialize() {
        ConfigLoader configLoader = new ConfigLoader();

        // Raster-cache the animated overlays once: transform/opacity animations
        // then run on cached textures instead of re-rasterizing every pulse.
        // Only nodes whose OWN transform/opacity animates are cached — parents
        // of continuously-animating children (e.g. the intro logo scale-up)
        // would invalidate their texture every frame.
                enableNodeCache(voiceOrb);
        enableNodeCache(voiceCard);
        enableNodeCache(notificationBanner);
        enableNodeCache(notificationAvatarGlow);
        enableNodeCache(alarmGlowPane);
        enableNodeCache(alarmManagerLayer);
        enableNodeCache(spotifyAuthLayer);
        enableNodeCache(calendarLayer);
        enableNodeCache(spotifyFullScreenLayer);

        // 1. Initialize Clock and Date updates (1-second tick)
        updateTime();
        Timeline clockTimeline = new Timeline(new KeyFrame(Duration.seconds(1), event -> updateTime()));
        clockTimeline.setCycleCount(Animation.INDEFINITE);
        clockTimeline.play();

        // 1b. Initialize Alarm System (persistence at ~/.alpha/alarms.json)
        alarmService = new AlarmService();
        buildDayChips();
        refreshNextAlarmHint();

        // 1c. Initialize Calendar System (public iCloud .ics share link).
        //     Cached payload renders instantly; network refresh follows.
        String icsUrl = configLoader.getProperty("CALENDAR_ICS_URL");
        if (icsUrl != null && !icsUrl.isBlank()) {
            calendarService = new CalendarService(icsUrl);
            calendarEvents = calendarService.loadCached();
            refreshCalendarBadge();
            calendarService.fetchAsync(
                    events -> Platform.runLater(() -> {
                        calendarEvents = events;
                        refreshNextEventHint();
                        refreshCalendarBadge();
                        if (calendarLayer != null && calendarLayer.isVisible()) {
                            rebuildCalendarGrid();
                        }
                    }),
                    error -> { /* offline: keep showing cached data */ });
        }

        // 1d. Initialize Photo Frame store (persistent library at ~/.alpha/photos)
        photoStore = new PhotoFrameService();
        photoLibrary = photoStore.listPhotos();
        enableNodeCache(photoLayer);

        // 2. Initialize Weather Service
        String apiKey = configLoader.getProperty("OPENWEATHER_API_KEY");
        String city = configLoader.getProperty("OPENWEATHER_CITY");
        String displayCity = city != null && !city.isBlank() ? city.split(",")[0].trim() : "Granada";

        if (weatherCityLabel != null) {
            weatherCityLabel.setText(displayCity);
        }

        boolean weatherConfigured = apiKey != null && !apiKey.isEmpty();
        if (weatherConfigured) {
            weatherService = new WeatherService(apiKey, city != null ? city : "Granada,ES");
            fetchWeather();
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

        // 4. Initialize Spotify Service (+ silent session restore / QR login)
        String clientId = configLoader.getProperty("SPOTIFY_CLIENT_ID");
        String clientSecret = configLoader.getProperty("SPOTIFY_CLIENT_SECRET");
        String redirectUri = configLoader.getProperty("SPOTIFY_REDIRECT_URI");

        boolean spotifyConfigured = clientId != null && clientSecret != null && redirectUri != null;
        if (spotifyConfigured) {
            spotifyService = new SpotifyService(clientId, clientSecret, redirectUri);
            System.out.println("Spotify service initialized.");

            // Embedded OAuth callback server on port 8888 (accepts phone callbacks too)
            spotifyService.startAuthCallbackServer(this::handleSpotifyCallback, 8888);

            restoreOrRequestSpotifySession();

            // Periodic polling for currently playing track (every 4 seconds)
            backgroundTicker.scheduleWithFixedDelay(this::pollCurrentlyPlaying, 0, 4, TimeUnit.SECONDS);
        }

        // 5. Initialize Telegram Bot Service (Remote Messaging from outside local network)
        String telegramToken = configLoader.getProperty("TELEGRAM_BOT_TOKEN");
        String telegramChatId = configLoader.getProperty("TELEGRAM_ALLOWED_CHAT_ID");

        if (telegramToken != null && !telegramToken.isBlank()) {
            telegramService = new TelegramService(telegramToken, telegramChatId, this::displayNotification);
            telegramService.setPhotoHandler(this::handleIncomingTelegramPhoto);
            telegramService.setCommandHandler(command -> {
                if ("foto".equals(command)) {
                    Platform.runLater(this::openPhotoFrame);
                }
            });
            telegramService.start();
        }

        // 6. Initialize Voice Assistant Front-End (polls Python backend on port 8090).
        //    When the backend is not reachable and lives next to the app, it is
        //    spawned automatically so a single jar run contains the whole system.
        String backendUrl = configLoader.getProperty("VOICE_BACKEND_URL");
        voiceService = new VoiceAssistantService(backendUrl);
        voiceBackendLauncher = new VoiceBackendLauncher(
                backendUrl,
                configLoader.getProperty("VOICE_BACKEND_DIR"),
                configLoader.getProperty("VOICE_PYTHON_BIN"));
        initVoiceAnimations();
        pollVoiceState();
        backgroundTicker.scheduleWithFixedDelay(this::pollVoiceState, 1, 1, TimeUnit.SECONDS);

        // 7. Shared slow tickers (kept off the FX animation clock)
        if (weatherConfigured) {
            backgroundTicker.scheduleWithFixedDelay(this::fetchWeather, 30, 30, TimeUnit.MINUTES);
        }
        if (calendarService != null) {
            backgroundTicker.scheduleWithFixedDelay(
                    () -> calendarService.fetchAsync(
                            events -> Platform.runLater(() -> {
                                calendarEvents = events;
                                refreshNextEventHint();
                                refreshCalendarBadge();
                                if (calendarLayer != null && calendarLayer.isVisible()) {
                                    rebuildCalendarGrid();
                                }
                            }),
                            error -> { /* offline: keep cached data */ }),
                    15, 15, TimeUnit.MINUTES);
        }
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
        // The date string only changes at midnight: skip the label write the
        // other 86399 ticks of the day (text layout is expensive on the Pi).
        if (dateLabel != null) {
            String date = ThemeManager.formatDate(now);
            if (!date.equals(cachedDateText)) {
                cachedDateText = date;
                dateLabel.setText(date);
            }
        }

        checkAlarms(now);
        if (now.getMinute() != lastAlarmHintMinute) {
            lastAlarmHintMinute = now.getMinute();
            refreshNextAlarmHint();
            refreshNextEventHint();
            refreshCalendarBadge();
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
            if (!isCurrentlyShowingSpotify) {
                // Transition from paused to playing: play Spotify logo intro transition
                showSpotifyWithTransition();
            }

            // Polling arrives every 4s with mostly identical data: only touch the
            // scene graph (text layout + image swap) when something really changed.
            String signature = track.title() + '\n' + track.artist() + '\n'
                    + track.album() + '\n' + track.deviceName();
            String coverUrl = track.coverUrl() == null ? "" : track.coverUrl();
            boolean coverChanged = !coverUrl.equals(currentCoverUrl == null ? "" : currentCoverUrl);
            if (coverChanged || !signature.equals(lastTrackSignature)) {
                lastTrackSignature = signature;
                populateTrackData(track);
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
        lastTrackSignature = null;

        // Spotify takes over the screen: hide all voice UI and any open sheet
        // (the assistant itself keeps running and can still execute actions).
        cancelReplyLinger();
        hideVoiceCard(true);
        voiceOverlayLayer.setVisible(false);
        closeCalendarScreen(true);
        closePhotoFrame(true);

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
        lastTrackSignature = null;

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
     * Displays a remote message inside Alpha's speech bubble: the assistant
     * "says" it with a slide-up + fade entrance and a softly pulsing avatar.
     * The message stays visible until touched on screen or cleared via Telegram /clear.
     *
     * @param message Notification message text (null/blank hides the bubble).
     */
    private void displayNotification(String message) {
        Platform.runLater(() -> {
            if (message == null || message.trim().isEmpty()) {
                hideNotificationBubble(true);
                return;
            }

            if (notificationLabel != null) {
                notificationLabel.setText(message.trim());
            }
            if (notificationBanner == null) {
                return;
            }

            boolean alreadyVisible = notificationBanner.isVisible();
            if (notificationFadeOut != null) {
                notificationFadeOut.stop();
                notificationFadeOut = null;
            }

            if (!alreadyVisible) {
                if (notificationSlideIn != null) {
                    notificationSlideIn.stop();
                }
                notificationBanner.setTranslateY(52.0);
                notificationBanner.setOpacity(0.0);
                notificationBanner.setVisible(true);
                startAvatarGlowPulse();

                notificationSlideIn = new Timeline(new KeyFrame(Duration.millis(340),
                        new KeyValue(notificationBanner.opacityProperty(), 1.0, Interpolator.EASE_OUT),
                        new KeyValue(notificationBanner.translateYProperty(), 0.0, Interpolator.EASE_OUT)));
                notificationSlideIn.setOnFinished(e -> {
                    notificationSlideIn = null;
                    // Drop the shadow-heavy entrance state; keep only transforms animated.
                });
                notificationSlideIn.play();
            } else {
                // New message replaces the old one with a gentle re-pop.
                if (notificationSlideIn != null) {
                    notificationSlideIn.stop();
                }
                notificationSlideIn = new Timeline(
                        new KeyFrame(Duration.millis(90),
                                new KeyValue(notificationBanner.scaleXProperty(), 0.97, Interpolator.EASE_BOTH),
                                new KeyValue(notificationBanner.scaleYProperty(), 0.97, Interpolator.EASE_BOTH)),
                        new KeyFrame(Duration.millis(200),
                                new KeyValue(notificationBanner.scaleXProperty(), 1.0, Interpolator.EASE_OUT),
                                new KeyValue(notificationBanner.scaleYProperty(), 1.0, Interpolator.EASE_OUT)));
                notificationSlideIn.setOnFinished(e -> notificationSlideIn = null);
                notificationSlideIn.play();
            }
        });
    }

    /**
     * Dismisses Alpha's speech bubble when tapped by the user on the touchscreen.
     */
    @FXML
    public void dismissNotification() {
        hideNotificationBubble(false);
    }

    /**
     * Hides the speech bubble, instantly or with a short fade-down.
     */
    private void hideNotificationBubble(boolean instant) {
        stopAvatarGlowPulse();
        if (notificationSlideIn != null) {
            notificationSlideIn.stop();
            notificationSlideIn = null;
        }
        if (notificationBanner == null || !notificationBanner.isVisible()) {
            return;
        }
        if (instant) {
            notificationBanner.setVisible(false);
            notificationBanner.setOpacity(0.0);
            notificationBanner.setTranslateY(0.0);
            notificationBanner.setScaleX(1.0);
            notificationBanner.setScaleY(1.0);
            if (notificationLabel != null) {
                notificationLabel.setText("");
            }
            return;
        }
        if (notificationFadeOut != null) {
            notificationFadeOut.stop();
        }
        notificationFadeOut = new FadeTransition(Duration.millis(220), notificationBanner);
        notificationFadeOut.setFromValue(notificationBanner.getOpacity());
        notificationFadeOut.setToValue(0.0);
        notificationFadeOut.setOnFinished(e -> {
            notificationFadeOut = null;
            notificationBanner.setVisible(false);
            notificationBanner.setTranslateY(0.0);
            notificationBanner.setScaleX(1.0);
            notificationBanner.setScaleY(1.0);
            if (notificationLabel != null) {
                notificationLabel.setText("");
            }
        });
        notificationFadeOut.play();
    }

    /**
     * Soft aurora pulse behind the avatar while the bubble is on screen
     * (single reusable timeline, opacity-only for Raspberry Pi performance).
     */
    private void startAvatarGlowPulse() {
        if (notificationAvatarGlow == null) {
            return;
        }
        if (avatarGlowPulse == null) {
            avatarGlowPulse = new Timeline(
                    new KeyFrame(Duration.ZERO,
                            new KeyValue(notificationAvatarGlow.opacityProperty(), 0.35, Interpolator.EASE_BOTH)),
                    new KeyFrame(Duration.millis(1100),
                            new KeyValue(notificationAvatarGlow.opacityProperty(), 0.9, Interpolator.EASE_BOTH)));
            avatarGlowPulse.setAutoReverse(true);
            avatarGlowPulse.setCycleCount(Animation.INDEFINITE);
        }
        notificationAvatarGlow.setVisible(true);
        avatarGlowPulse.playFrom(Duration.ZERO);
    }

    private void stopAvatarGlowPulse() {
        if (avatarGlowPulse != null && avatarGlowPulse.getStatus() == Animation.Status.RUNNING) {
            avatarGlowPulse.stop();
        }
        if (notificationAvatarGlow != null) {
            notificationAvatarGlow.setVisible(false);
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
    public void handleSpotifyCallback(String authorizationCode) {
        if (spotifyService != null) {
            spotifyExecutor.submit(() -> {
                boolean success = spotifyService.exchangeCodeForTokens(authorizationCode);
                Platform.runLater(() -> {
                    if (success) {
                        System.out.println("Spotify successfully authorized.");
                        onSpotifyConnected();
                    } else {
                        System.err.println("Spotify authorization failed.");
                        if (spotifyAuthStatusLabel != null && spotifyAuthLayer.isVisible()) {
                            spotifyAuthStatusLabel.setText("No se pudo conectar, escanea de nuevo");
                            spotifyAuthStatusLabel.setStyle("-fx-text-fill: #ef4444;");
                        }
                        // Regenerate the QR so a fresh authorization URL is shown
                        refreshSpotifyQr();
                    }
                });
                if (success) {
                    pollCurrentlyPlaying();
                }
            });
        }
    }

    // =========================================================================
    // SPOTIFY QR LOGIN (scan with the phone, authorize, done)
    // =========================================================================

    /**
     * Tries a silent session restore from ~/.alpha/spotify-tokens.json.
     * Falls back to the on-screen QR login overlay when no usable session exists.
     */
    private void restoreOrRequestSpotifySession() {
        if (!spotifyService.restorePersistedSession()) {
            System.out.println("Spotify: no stored session. Showing QR login.");
            openSpotifyAuth();
            return;
        }
        spotifyExecutor.submit(() -> {
            boolean refreshed = spotifyService.refreshAccessToken();
            Platform.runLater(() -> {
                if (refreshed) {
                    System.out.println("Spotify session restored from stored tokens.");
                } else {
                    System.out.println("Spotify stored session rejected. Showing QR login.");
                    openSpotifyAuth();
                }
            });
        });
    }

    /** Spotify icon button: opens the QR login sheet on demand. */
    @FXML
    public void openSpotifyAuth() {
        if (spotifyService == null || spotifyAuthLayer == null || spotifyAuthLayer.isVisible()) {
            return;
        }
        refreshSpotifyQr();
        spotifyAuthLayer.setOpacity(0.0);
        spotifyAuthLayer.setVisible(true);
        FadeTransition fadeIn = new FadeTransition(Duration.millis(200), spotifyAuthLayer);
        fadeIn.setFromValue(0.0);
        fadeIn.setToValue(1.0);
        fadeIn.play();
    }

    @FXML
    public void closeSpotifyAuth() {
        if (spotifyAuthLayer == null || !spotifyAuthLayer.isVisible()) {
            return;
        }
        FadeTransition fadeOut = new FadeTransition(Duration.millis(180), spotifyAuthLayer);
        fadeOut.setToValue(0.0);
        fadeOut.setOnFinished(e -> spotifyAuthLayer.setVisible(false));
        fadeOut.play();
    }

    /** Closes the sheet only when the backdrop itself is clicked, not its children. */
    @FXML
    public void closeSpotifyAuthOnOutsideClick(MouseEvent event) {
        if (event.getTarget() == spotifyAuthLayer) {
            closeSpotifyAuth();
        }
    }

    /** Keeps clicks inside the card from bubbling to the backdrop handler. */
    @FXML
    public void swallowSpotifyAuthClick(MouseEvent event) {
        event.consume();
    }

    /**
     * Builds a fresh authorization URL and renders it as an on-screen QR code
     * that any phone camera can scan to complete the Spotify login.
     */
    private void refreshSpotifyQr() {
        if (spotifyService == null || spotifyAuthQrView == null) {
            return;
        }
        try {
            String authUrl = spotifyService.getAuthorizationUri();
            System.out.println("Spotify Auth URL: " + authUrl);

            BitMatrix matrix = SpotifyQrGenerator.generate(authUrl);
            if (matrix == null) {
                spotifyAuthStatusLabel.setText("No se pudo generar el QR");
                return;
            }
            int size = Math.max(matrix.getWidth(), 1);
            WritableImage qrImage = new WritableImage(size, size);
            PixelWriter writer = qrImage.getPixelWriter();
            int[] buffer = new int[size * size];
            for (int y = 0; y < matrix.getHeight(); y++) {
                for (int x = 0; x < matrix.getWidth(); x++) {
                    buffer[y * size + x] = matrix.get(x, y) ? 0xFF111827 : 0xFFFFFFFF;
                }
            }
            writer.setPixels(0, 0, size, size,
                    PixelFormat.getIntArgbInstance(), buffer, 0, size);
            spotifyAuthQrView.setImage(qrImage);

            if (spotifyAuthStatusLabel != null) {
                spotifyAuthStatusLabel.setText("Esperando conexi\u00f3n...");
                spotifyAuthStatusLabel.setStyle(null);
            }
        } catch (Exception e) {
            System.err.println("Failed to generate Spotify Auth URL: " + e.getMessage());
            if (spotifyAuthStatusLabel != null) {
                spotifyAuthStatusLabel.setText("Error generando el enlace de acceso");
            }
        }
    }

    /**
     * Called on the FX thread once tokens are stored: dismisses the QR sheet
     * and confirms through Alpha's speech bubble.
     */
    private void onSpotifyConnected() {
        if (spotifyAuthLayer != null && spotifyAuthLayer.isVisible()) {
            closeSpotifyAuth();
        }
        displayNotification("\u2705 Spotify conectado");
    }

    /**
     * Raster-caches a node so opacity/transform animations composite the cached
     * texture instead of re-rendering gradients/effects each pulse.
     */
    private static void enableNodeCache(Node node) {
        if (node != null) {
            node.setCache(true);
        }
    }

    // =========================================================================
    // CALENDAR SCREEN (full-screen agenda: month grid + day event list)
    // =========================================================================

    /** Calendar icon button: opens the agenda on the current month. */
    @FXML
    public void openCalendarScreen() {
        if (calendarLayer == null || calendarLayer.isVisible()) {
            return;
        }
        LocalDate today = LocalDate.now();
        displayedMonth = YearMonth.from(today);
        selectCalendarDate(today);

        calendarLayer.setOpacity(0.0);
        calendarLayer.setVisible(true);
        FadeTransition fadeIn = new FadeTransition(Duration.millis(200), calendarLayer);
        fadeIn.setFromValue(0.0);
        fadeIn.setToValue(1.0);
        fadeIn.play();
    }

    @FXML
    public void closeCalendarScreen() {
        closeCalendarScreen(false);
    }

    private void closeCalendarScreen(boolean instant) {
        if (calendarLayer == null || !calendarLayer.isVisible()) {
            return;
        }
        if (instant) {
            calendarLayer.setVisible(false);
            calendarLayer.setOpacity(0.0);
            return;
        }
        FadeTransition fadeOut = new FadeTransition(Duration.millis(180), calendarLayer);
        fadeOut.setToValue(0.0);
        fadeOut.setOnFinished(e -> calendarLayer.setVisible(false));
        fadeOut.play();
    }

    /** Closes the screen only when the backdrop itself is clicked, not its children. */
    @FXML
    public void closeCalendarOnOutsideClick(MouseEvent event) {
        if (event.getTarget() == calendarLayer) {
            closeCalendarScreen();
        }
    }

    /** Keeps clicks inside the card from bubbling to the backdrop handler. */
    @FXML
    public void swallowCalendarClick(MouseEvent event) {
        event.consume();
    }

    @FXML
    public void showPreviousMonth() {
        navigateMonth(-1);
    }

    @FXML
    public void showNextMonth() {
        navigateMonth(1);
    }

    private void navigateMonth(int delta) {
        displayedMonth = displayedMonth.plusMonths(delta);
        rebuildCalendarGrid();
    }

    private void selectCalendarDate(LocalDate date) {
        selectedCalendarDate = date;
        rebuildCalendarGrid();
    }

    /**
     * Rebuilds the 6x7 month grid (Monday-first). Cells are plain labels with
     * pre-baked style classes: no effects, built once per navigation/refresh,
     * selection toggles a single style class without rebuilding anything.
     */
    private void rebuildCalendarGrid() {
        if (calendarGrid == null || displayedMonth == null) {
            return;
        }
        String monthTitle = capitalize(displayedMonth.format(CALENDAR_MONTH_FORMAT));
        calendarTitleLabel.setText(monthTitle);

        calendarDayCells.clear();
        calendarGrid.getChildren().clear();

        LocalDate firstOfMonth = displayedMonth.atDay(1);
        LocalDate gridStart = firstOfMonth.with(java.time.temporal.TemporalAdjusters
                .previousOrSame(DayOfWeek.MONDAY));

        for (int week = 0; week < 6; week++) {
            HBox row = new HBox(6);
            for (int day = 0; day < 7; day++) {
                LocalDate cellDate = gridStart.plusDays(week * 7L + day);
                row.getChildren().add(buildDayCell(cellDate,
                        YearMonth.from(cellDate).equals(displayedMonth)));
            }
            calendarGrid.getChildren().add(row);
        }
        highlightSelectedCell(null);
        rebuildDayAgenda();
    }

    private VBox buildDayCell(LocalDate date, boolean inDisplayedMonth) {
        VBox cell = new VBox(3);
        cell.setAlignment(javafx.geometry.Pos.TOP_CENTER);
        cell.getStyleClass().add("cal-day");
        cell.setUserData(date);

        Label number = new Label(String.valueOf(date.getDayOfMonth()));
        number.getStyleClass().add("cal-day-number");
        cell.getChildren().add(number);

        List<CalendarEvent> dayEvents = eventsOn(date);
        if (!dayEvents.isEmpty()) {
            HBox dots = new HBox(3);
            dots.getStyleClass().add("cal-dots-row");
            int shown = 0;
            for (CalendarEvent event : dayEvents) {
                if (shown >= 3) {
                    break;
                }
                Label dot = new Label();
                dot.getStyleClass().add("cal-dot");
                // All-day events get a sky-blue dot, timed ones violet
                dot.setStyle("-fx-background-color: "
                        + (event.allDay() ? "#38bdf8;" : "#a855f7;"));
                dots.getChildren().add(dot);
                shown++;
            }
            cell.getChildren().add(dots);
        }

        if (!inDisplayedMonth) {
            cell.getStyleClass().add("cal-day-other");
        }
        if (date.equals(LocalDate.now())) {
            cell.getStyleClass().add("cal-day-today");
        }

        cell.setOnMouseClicked(e -> {
            selectedCalendarDate = date;
            highlightSelectedCell(cell);
            rebuildDayAgenda();
        });

        calendarDayCells.put(date, cell);
        if (date.equals(selectedCalendarDate)) {
            cell.getStyleClass().add("cal-day-selected");
        }
        return cell;
    }

    private void highlightSelectedCell(VBox newlySelected) {
        for (VBox cell : calendarDayCells.values()) {
            cell.getStyleClass().remove("cal-day-selected");
        }
        if (newlySelected != null && !newlySelected.getStyleClass().contains("cal-day-selected")) {
            newlySelected.getStyleClass().add("cal-day-selected");
        }
    }

    private void rebuildDayAgenda() {
        if (calendarEventList == null || selectedCalendarDate == null) {
            return;
        }
        String dayTitle = capitalize(selectedCalendarDate.format(CALENDAR_DAY_FORMAT));
        calendarSelectedDateLabel.setText(dayTitle);

        calendarEventList.getChildren().clear();
        List<CalendarEvent> dayEvents = eventsOn(selectedCalendarDate);
        if (dayEvents.isEmpty()) {
            Label empty = new Label("No hay eventos este d\u00eda");
            empty.getStyleClass().add("cal-empty-hint");
            empty.setWrapText(true);
            calendarEventList.getChildren().add(empty);
            return;
        }
        for (CalendarEvent event : dayEvents) {
            VBox card = new VBox(2);
            card.getStyleClass().add("cal-event-card");

            Label time = new Label(event.displayTime());
            time.getStyleClass().add("cal-event-time");
            Label title = new Label(event.title().isBlank() ? "(sin t\u00edtulo)" : event.title());
            title.getStyleClass().add("cal-event-title");
            title.setWrapText(true);

            card.getChildren().addAll(time, title);
            calendarEventList.getChildren().add(card);
        }
    }

    /**
     * Violet badge over the calendar icon button: how many events start
     * within the next 7 days (hidden when there are none).
     */
    private void refreshCalendarBadge() {
        if (calendarBadgeLabel == null || calendarService == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime limit = now.plusDays(7);
        int count = 0;
        for (CalendarEvent event : calendarEvents) {
            if (!event.start().isBefore(now) && event.start().isBefore(limit)) {
                count++;
            }
        }
        calendarBadgeLabel.setVisible(count > 0);
        calendarBadgeLabel.setText(String.valueOf(count));
    }

    /** Pre-sorted lookup of events overlapping the given date (linear scan; lists are small). */
    private List<CalendarEvent> eventsOn(LocalDate date) {
        if (calendarEvents.isEmpty()) {
            return List.of();
        }
        List<CalendarEvent> matches = new ArrayList<>(4);
        for (CalendarEvent event : calendarEvents) {
            if (event.overlapsDate(date)) {
                matches.add(event);
            }
        }
        return matches;
    }

    /**
     * Subtle next-event hint under the weather row (same family as the
     * next-alarm hint). Refreshes once per minute and on every feed refresh.
     */
    private void refreshNextEventHint() {
        if (nextEventRow == null || nextEventLabel == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        CalendarEvent next = null;
        for (CalendarEvent event : calendarEvents) {
            if (event.start().isAfter(now)) {
                next = event;
                break;
            }
        }

        String summary = null;
        if (next != null) {
            String when;
            LocalDate startDate = next.startDate();
            if (startDate.equals(now.toLocalDate())) {
                when = "Hoy";
            } else if (startDate.equals(now.toLocalDate().plusDays(1))) {
                when = "Ma\u00f1ana";
            } else {
                when = startDate.getDayOfMonth() + "/" + startDate.getMonthValue();
            }
            String timePart = next.allDay() ? "" : " " + next.start().format(DateTimeFormatter.ofPattern("HH:mm"));
            String title = next.title();
            if (title.length() > 26) {
                title = title.substring(0, 26) + "\u2026";
            }
            summary = title + " \u00b7 " + when + timePart;
        }

        nextEventRow.setVisible(summary != null);
        nextEventLabel.setText(summary != null ? summary : "");
    }

    // =========================================================================
    // PHOTO FRAME (full-screen slideshow over ~/.alpha/photos)
    // =========================================================================

    /**
     * Telegram photo hook (runs on the polling thread): persists the bytes and
     * reveals the new photo full-screen with an Alpha confirmation bubble.
     */
    private boolean handleIncomingTelegramPhoto(TelegramService.TelegramPhoto photo, byte[] data) {
        try {
            Path saved = photoStore.save(data);
            Platform.runLater(() -> {
                photoLibrary = photoStore.listPhotos();
                displayNotification("\ud83d\udcf8 Foto a\u00f1adida al marco");
                int index = photoLibrary.indexOf(saved);
                showPhotoAt(index >= 0 ? index : photoLibrary.size() - 1);
            });
            System.out.println("PhotoFrame: stored Telegram photo (" + data.length + " bytes)");
            return true;
        } catch (Exception e) {
            System.err.println("PhotoFrame: failed to store Telegram photo: " + e.getMessage());
            return false;
        }
    }

    /** Photo icon button: opens the ambient slideshow on the most recent photo. */
    @FXML
    public void openPhotoFrame() {
        if (photoLayer == null || photoLayer.isVisible()) {
            return;
        }
        photoLibrary = photoStore.listPhotos();
        if (photoLibrary.isEmpty()) {
            displayNotification("\ud83d\uddbc\ufe0f El marco est\u00e1 vac\u00edo: m\u00e1ndame una foto por Telegram");
            return;
        }
        currentPhotoIndex = -1;
        presentPhotoLayer();
        displayPhoto(photoLibrary.size() - 1);
    }

    @FXML
    public void closePhotoFrame() {
        closePhotoFrame(false);
    }

    private void closePhotoFrame(boolean instant) {
        if (photoLayer == null || !photoLayer.isVisible()) {
            return;
        }
        if (photoSlideTimer != null) {
            photoSlideTimer.stop();
        }
        if (photoCrossFade != null) {
            photoCrossFade.stop();
            photoCrossFade = null;
        }
        Runnable finish = () -> {
            photoLayer.setVisible(false);
            photoLayer.setOpacity(0.0);
            photoViewA.setImage(null);
            photoViewB.setImage(null);
            photoViewA.setOpacity(1.0);
            photoViewB.setOpacity(0.0);
            photoFrontIsA = true;
            currentPhotoIndex = -1;
            preloadedPhoto = null;
            preloadedPhotoPath = null;
            // Spotify owns the overlay lifecycle while it is playing.
            if (!isCurrentlyShowingSpotify) {
                voiceOverlayLayer.setVisible(true);
            }
        };
        if (instant) {
            finish.run();
            return;
        }
        FadeTransition fadeOut = new FadeTransition(Duration.millis(220), photoLayer);
        fadeOut.setToValue(0.0);
        fadeOut.setOnFinished(e -> finish.run());
        fadeOut.play();
    }

    /** Reveals the frame over everything except notifications/alarms. */
    private void presentPhotoLayer() {
        closeCalendarScreen(true);
        voiceOverlayLayer.setVisible(false);
        photoLayer.setOpacity(0.0);
        photoLayer.setVisible(true);
        FadeTransition fadeIn = new FadeTransition(Duration.millis(250), photoLayer);
        fadeIn.setFromValue(0.0);
        fadeIn.setToValue(1.0);
        fadeIn.play();
    }

    /**
     * Crossfades to the photo at the given index (wrapped). Identical indices
     * only restart the slide timer so duplicate callbacks never re-decode.
     */
    private void showPhotoAt(int index) {
        if (photoLayer == null || photoLibrary.isEmpty()) {
            return;
        }
        int target = Math.floorMod(index, photoLibrary.size());
        if (!photoLayer.isVisible()) {
            currentPhotoIndex = -1;
            presentPhotoLayer();
        }
        if (target == currentPhotoIndex) {
            scheduleNextSlide();
            return;
        }
        displayPhoto(target);
    }

    @FXML
    public void showPreviousPhoto() {
        navigatePhotos(-1);
    }

    @FXML
    public void showNextPhoto() {
        navigatePhotos(1);
    }

    private void navigatePhotos(int delta) {
        if (!photoLayer.isVisible() || photoLibrary.isEmpty()) {
            return;
        }
        int base = currentPhotoIndex < 0 ? 0 : currentPhotoIndex;
        displayPhoto(Math.floorMod(base + delta, photoLibrary.size()));
        scheduleNextSlide();
    }

    /**
     * Advances to the given library slot, skipping corrupt files (bounded by
     * the library size). Uses two stacked ImageViews so transitions are pure
     * opacity fades; the replaced bitmap is released as soon as it is covered.
     */
    private void displayPhoto(int startIndex) {
        ImageView front = photoFrontIsA ? photoViewA : photoViewB;
        ImageView back = photoFrontIsA ? photoViewB : photoViewA;

        // An interrupted fade leaves two populated views: reset the hidden one.
        if (photoCrossFade != null) {
            photoCrossFade.stop();
            photoCrossFade = null;
            front.setOpacity(1.0);
        }
        back.setImage(null);
        back.setOpacity(0.0);

        Image image = null;
        int index = startIndex;
        for (int attempts = photoLibrary.size(); attempts > 0; attempts--) {
            currentPhotoIndex = index;
            image = takePreloaded(photoLibrary.get(index));
            if (!image.isError()) {
                break;
            }
            System.err.println("PhotoFrame: unreadable image skipped: " + photoLibrary.get(index));
            image = null;
            index = Math.floorMod(index + 1, photoLibrary.size());
        }
        if (image == null) {
            closePhotoFrame(true);
            displayNotification("\u26a0\ufe0f No hay fotos legibles en el marco");
            return;
        }

        back.setImage(image);
        photoFrontIsA = !photoFrontIsA;
        updatePhotoCounter();

        if (front.getImage() == null) {
            preloadNextPhoto();
            scheduleNextSlide();
            return; // very first paint: nothing to fade from
        }

        photoCrossFade = new FadeTransition(Duration.millis(900), back);
        photoCrossFade.setFromValue(0.0);
        photoCrossFade.setToValue(1.0);
        photoCrossFade.setOnFinished(e -> {
            front.setImage(null); // release the replaced bitmap (Pi RAM)
            front.setOpacity(0.0);
            photoCrossFade = null;
            preloadNextPhoto();
        });
        photoCrossFade.play();
        scheduleNextSlide();
    }

    /**
     * One-shot 15s pause between slides; recreated never, restarted always,
     * so periodic work stays off the FX animation clock between fires.
     */
    private void scheduleNextSlide() {
        if (photoSlideTimer == null) {
            photoSlideTimer = new PauseTransition(Duration.seconds(15));
            photoSlideTimer.setOnFinished(e -> {
                if (photoLayer.isVisible() && photoLibrary.size() > 1) {
                    displayPhoto((currentPhotoIndex + 1) % photoLibrary.size());
                }
            });
        }
        photoSlideTimer.playFrom(Duration.ZERO);
    }

    /**
     * Decodes the next photo ahead of time so crossfades are instant even on
     * the Pi: backgroundLoading keeps the decode off the FX thread and the
     * downscale to screen size caps each bitmap at ~2.4 MB.
     */
    private void preloadNextPhoto() {
        if (photoLibrary.isEmpty() || photoLibrary.size() < 2) {
            return;
        }
        Path nextPath = photoLibrary.get((currentPhotoIndex + 1) % photoLibrary.size());
        if (nextPath.equals(preloadedPhotoPath)) {
            return;
        }
        preloadedPhotoPath = nextPath;
        preloadedPhoto = createPhotoImage(nextPath);
    }

    private Image takePreloaded(Path path) {
        if (preloadedPhoto != null && path.equals(preloadedPhotoPath)) {
            Image image = preloadedPhoto;
            preloadedPhoto = null;
            preloadedPhotoPath = null;
            return image;
        }
        return createPhotoImage(path);
    }

    private static Image createPhotoImage(Path path) {
        return new Image(path.toUri().toString(), 1024, 600, true, true, true);
    }

    private void updatePhotoCounter() {
        if (photoCounterLabel != null) {
            photoCounterLabel.setText((currentPhotoIndex + 1) + " / " + photoLibrary.size());
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
     * When the backend is unreachable — or the mic is muted and the UI is
     * frozen anyway — backs off to one attempt every 5 seconds.
     */
    private void pollVoiceState() {
        if (voiceService == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if ((!voiceBackendOnline || voiceMuted) && now - lastVoicePollAttemptMs < 5000) {
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
            maybeSpawnVoiceBackend();
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
     * When the backend is unreachable, spawns the local Python service
     * (throttled) so a plain `java -jar` run contains the whole assistant.
     * The launcher itself gives up after a missing directory and retries only
     * genuine start failures.
     */
    private void maybeSpawnVoiceBackend() {
        if (voiceBackendLauncher == null || voiceBackendLauncher.isAlive()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastVoiceSpawnAttemptMs < 20000) {
            return;
        }
        lastVoiceSpawnAttemptMs = now;
        voiceExecutor.submit(voiceBackendLauncher::ensureRunning);
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
                double opacity = 0.78 + 0.22 * score;
                // Value-deduplicated: the poll arrives every second with a mostly
                // stable score, and an unchanged opacity write still marks the
                // cached orb dirty and forces a composite pass on the Pi.
                if (opacity != lastVoiceOrbOpacity) {
                    lastVoiceOrbOpacity = opacity;
                    voiceOrb.setOpacity(opacity);
                }
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
        // Reset the IDLE-optimised opacity cache so the next dynamic poll write
        // re-applies (this method forcibly replaces the orb opacity otherwise).
        lastVoiceOrbOpacity = -1.0;
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

        // No sheet may stay above the ringing overlay
        if (alarmManagerLayer.isVisible()) {
            closeAlarmManager();
        }
        closeCalendarScreen(true);
        closePhotoFrame(true);

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
        if (voiceBackendLauncher != null) {
            voiceBackendLauncher.stop();
        }
        backgroundTicker.shutdownNow();
        spotifyExecutor.shutdownNow();
        voiceExecutor.shutdownNow();
        stopOrbBreathing();
        stopThinkingDots();
        cancelReplyLinger();
        stopGlowPulse();
        stopAvatarGlowPulse();
        if (notificationSlideIn != null) {
            notificationSlideIn.stop();
            notificationSlideIn = null;
        }
        if (notificationFadeOut != null) {
            notificationFadeOut.stop();
            notificationFadeOut = null;
        }
        if (photoSlideTimer != null) {
            photoSlideTimer.stop();
            photoSlideTimer = null;
        }
        if (photoCrossFade != null) {
            photoCrossFade.stop();
            photoCrossFade = null;
        }
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
