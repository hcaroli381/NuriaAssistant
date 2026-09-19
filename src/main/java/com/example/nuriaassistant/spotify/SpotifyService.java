package com.example.nuriaassistant.spotify;
import com.example.nuriaassistant.util.Log;

import com.example.nuriaassistant.models.SpotifyTrackData;
import com.sun.net.httpserver.HttpServer;
import org.apache.hc.core5.http.ParseException;
import se.michaelthelin.spotify.SpotifyApi;
import se.michaelthelin.spotify.exceptions.SpotifyWebApiException;
import se.michaelthelin.spotify.model_objects.IPlaylistItem;
import se.michaelthelin.spotify.model_objects.credentials.AuthorizationCodeCredentials;
import se.michaelthelin.spotify.model_objects.miscellaneous.CurrentlyPlaying;
import se.michaelthelin.spotify.model_objects.miscellaneous.CurrentlyPlayingContext;
import se.michaelthelin.spotify.model_objects.specification.AlbumSimplified;
import se.michaelthelin.spotify.model_objects.specification.ArtistSimplified;
import se.michaelthelin.spotify.model_objects.specification.Episode;
import se.michaelthelin.spotify.model_objects.specification.Image;
import se.michaelthelin.spotify.model_objects.specification.Track;
import se.michaelthelin.spotify.requests.authorization.authorization_code.AuthorizationCodeRefreshRequest;
import se.michaelthelin.spotify.requests.authorization.authorization_code.AuthorizationCodeRequest;
import se.michaelthelin.spotify.requests.authorization.authorization_code.AuthorizationCodeUriRequest;
import se.michaelthelin.spotify.requests.data.player.GetInformationAboutUsersCurrentPlaybackRequest;
import se.michaelthelin.spotify.requests.data.player.GetUsersCurrentlyPlayingTrackRequest;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Service for interacting with Spotify Web API, retrieving currently playing
 * track metadata, song images, song names, artist names, speaker device info,
 * and managing OAuth authentication.
 */
public class SpotifyService {

    public enum ImageSizePreference {
        LARGE,   // Typically 640x640 (crisp for full-screen display)
        MEDIUM,  // Typically 300x300 (optimal memory balance)
        SMALL    // Typically 64x64
    }

    private final SpotifyApi spotifyApi;
    private final SpotifyTokenStore tokenStore;
    private HttpServer authCallbackServer;

    /**
     * Redirect URI the current authorization code was requested with (null =
     * the client's configured URI). Written on the FX thread when the QR is
     * regenerated, read by the callback server thread during the exchange.
     */
    private volatile String codeRedirectUri;

    public SpotifyService(String clientId, String clientSecret, String redirectUri) {
        this(clientId, clientSecret, redirectUri, new SpotifyTokenStore());
    }

    public SpotifyService(String clientId, String clientSecret, String redirectUri, SpotifyTokenStore tokenStore) {
        this.spotifyApi = new SpotifyApi.Builder()
                .setClientId(clientId)
                .setClientSecret(clientSecret)
                .setRedirectUri(URI.create(redirectUri))
                .build();
        this.tokenStore = tokenStore != null ? tokenStore : new SpotifyTokenStore();
    }

    /**
     * True when an access token is currently loaded in the API client.
     */
    public boolean isAuthenticated() {
        String token = spotifyApi.getAccessToken();
        return token != null && !token.isEmpty();
    }

    /**
     * Restores a previously persisted session from ~/.alpha/spotify-tokens.json.
     * Returns true when a refresh token exists (silent refresh possible);
     * false means the user must complete the QR login again.
     */
    public synchronized boolean restorePersistedSession() {
        if (!tokenStore.load()) {
            return false;
        }
        spotifyApi.setAccessToken(tokenStore.accessToken());
        spotifyApi.setRefreshToken(tokenStore.refreshToken());
        return true;
    }

    /**
     * Generates the authorization URL to redirect the user to for logging into Spotify.
     *
     * @return Spotify authorization URL.
     * @throws IOException            If network error occurs.
     * @throws SpotifyWebApiException If Spotify API error occurs.
     * @throws ParseException         If parsing error occurs.
     */
    public String getAuthorizationUri() throws IOException, SpotifyWebApiException, ParseException {
        return getAuthorizationUri(null, null);
    }

    /**
     * Builds the authorize URL for a specific redirect URI (overriding the one
     * configured on the API client) and state value. The phone QR login uses
     * this to point the callback at the whitelisted HTTPS bounce page while
     * carrying this Pi's LAN endpoint inside the state.
     *
     * @param redirectUri HTTPS bounce page, or null to use the configured URI.
     * @param state       Opaque state echoed back by Spotify, or null for none.
     * @return Spotify authorization URL.
     */
    public String getAuthorizationUri(String redirectUri, String state)
            throws IOException, SpotifyWebApiException, ParseException {
        AuthorizationCodeUriRequest.Builder builder = spotifyApi.authorizationCodeUri()
                .scope("user-read-currently-playing,user-read-playback-state")
                .show_dialog(false);

        if (redirectUri != null && !redirectUri.isBlank()) {
            builder.redirect_uri(URI.create(redirectUri));
        }
        if (state != null && !state.isBlank()) {
            builder.state(state);
        }

        // Spotify issues the code for one specific redirect URI and rejects the
        // exchange unless the two match exactly, so remember which one this code
        // belongs to instead of trusting the client's configured (loopback) URI.
        codeRedirectUri = (redirectUri != null && !redirectUri.isBlank()) ? redirectUri : null;

        URI uri = builder.build().execute();
        return uri.toString();
    }

    /**
     * Redirect URI the next code exchange will send: whatever the last authorize
     * URL was built with, or {@code null} to use the client's configured URI.
     * Exposed so the invariant can be asserted without a network round trip.
     */
    public String redirectUriForCodeExchange() {
        return codeRedirectUri;
    }

    /** One OAuth callback: the code Spotify issued, plus the state we sent. */
    public record AuthCallback(String code, String state, String error) {

        public boolean hasCode() {
            return code != null && !code.isBlank();
        }
    }

    /**
     * Starts an embedded HTTP server to automatically receive the OAuth callback code on the specified port.
     * Bound to {@code 0.0.0.0} so the phone can hand the code back over the LAN.
     *
     * @param onCodeReceived Receives the callback and returns true when the app
     *                       accepted it (drives the confirmation page on the phone).
     * @param port           Port to listen on (e.g. 8888).
     */
    public void startAuthCallbackServer(Function<AuthCallback, Boolean> onCodeReceived, int port) {
        if (authCallbackServer != null) {
            return;
        }
        try {
            authCallbackServer = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
            authCallbackServer.createContext(SpotifyPairing.PING_PATH, exchange -> {
                Log.info("Spotify", "OAuth ping from " + exchange.getRemoteAddress() + " — the phone can reach this Pi.");
                respond(exchange, 200, callbackPage("Alpha te oye",
                        "Tu m\u00f3vil y Alpha se ven en la red local. Ya puedes volver a escanear el QR de la pantalla."));
            });
            authCallbackServer.createContext(SpotifyPairing.CALLBACK_PATH, exchange -> {
                Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());
                AuthCallback callback = new AuthCallback(
                        params.get("code"), params.get("state"), params.get("error"));

                Log.info("Spotify", "OAuth callback from " + exchange.getRemoteAddress()
                        + " (code=" + (callback.hasCode() ? "yes" : "no")
                        + ", state=" + (callback.state() != null ? "yes" : "no")
                        + ", error=" + (callback.error() != null ? callback.error() : "none") + ")");

                boolean accepted = false;
                if (callback.hasCode() && onCodeReceived != null) {
                    accepted = Boolean.TRUE.equals(onCodeReceived.apply(callback));
                }

                String response = accepted
                        ? callbackPage("Listo", "Spotify conectado. Ya puedes cerrar esta pesta\u00f1a.")
                        : callbackPage("Vaya, no se pudo conectar",
                                (callback.hasCode()
                                        ? "El c\u00f3digo caduc\u00f3 o es de otro intento. "
                                        : "No lleg\u00f3 ning\u00fan c\u00f3digo. ")
                                        + "Vuelve a escanear el QR de la pantalla.");

                Log.info("Spotify", "OAuth callback " + (accepted ? "accepted" : "rejected") + ".");
                respond(exchange, accepted ? 200 : 400, response);
            });
            authCallbackServer.setExecutor(null);
            authCallbackServer.start();
            Log.info("Spotify", "Spotify OAuth callback server started on port " + port);
        } catch (IOException e) {
            Log.error("Spotify", "Failed to start Spotify OAuth callback server on port " + port + ": " + e.getMessage());
        }
    }

    /** Writes a small UTF-8 HTML response back to the phone. */
    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String html)
            throws IOException {
        byte[] body = html.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    /** URL-decodes a raw query string into its parameters (order-independent). */
    private static Map<String, String> parseQuery(String query) {
        Map<String, String> params = new LinkedHashMap<>();
        if (query == null || query.isBlank()) {
            return params;
        }
        for (String param : query.split("&")) {
            int equals = param.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String key = java.net.URLDecoder.decode(param.substring(0, equals), java.nio.charset.StandardCharsets.UTF_8);
            String value = java.net.URLDecoder.decode(param.substring(equals + 1), java.nio.charset.StandardCharsets.UTF_8);
            params.put(key, value);
        }
        return params;
    }

    /** Alpha-branded page shown on the phone once the Pi has the code. */
    private static String callbackPage(String title, String message) {
        return "<!DOCTYPE html><html lang='es'><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width, initial-scale=1'>"
                + "<title>Alpha \u00b7 Spotify</title></head>"
                + "<body style='margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;"
                + "background:radial-gradient(120% 90% at 20% 15%, #132a4a 0%, #0a192f 55%, #050d18 100%);"
                + "color:#ffffff;font-family:-apple-system,BlinkMacSystemFont,Segoe UI,Roboto,sans-serif;text-align:center;padding:32px;'>"
                + "<div style='max-width:420px'>"
                + "<div style='font-size:12px;letter-spacing:4px;color:#c084fc;font-weight:800;margin-bottom:18px;'>ALPHA</div>"
                + "<h1 style='font-size:26px;margin:0 0 12px;'>" + title + "</h1>"
                + "<p style='font-size:17px;line-height:1.5;color:#cbd5e1;margin:0;'>" + message + "</p>"
                + "</div></body></html>";
    }

    /**
     * Stops the OAuth callback server if running.
     */
    public void stopAuthCallbackServer() {
        if (authCallbackServer != null) {
            authCallbackServer.stop(0);
            authCallbackServer = null;
            Log.info("Spotify", "Spotify OAuth callback server stopped.");
        }
    }

    /**
     * Exchanges the authorization code received from OAuth callback for access and refresh tokens.
     *
     * @param authorizationCode Code received from Spotify OAuth redirect.
     * @return True if tokens were successfully obtained and saved, false otherwise.
     */
    /**
     * Builds the token exchange for a code, redeeming it against the redirect
     * URI the code was issued for. Split out from the exchange itself so the
     * value that goes on the wire can be asserted without a network round trip:
     * a mismatch here fails the whole QR login, silently.
     */
    AuthorizationCodeRequest buildCodeExchange(String authorizationCode) {
        AuthorizationCodeRequest.Builder builder = spotifyApi.authorizationCode(authorizationCode);
        if (codeRedirectUri != null) {
            builder.redirect_uri(URI.create(codeRedirectUri));
        }
        return builder.build();
    }

    public boolean exchangeCodeForTokens(String authorizationCode) {
        try {
            if (codeRedirectUri != null) {
                Log.info("Spotify", "Exchanging authorization code against redirect URI " + codeRedirectUri);
            }
            AuthorizationCodeRequest authorizationCodeRequest = buildCodeExchange(authorizationCode);

            AuthorizationCodeCredentials credentials = authorizationCodeRequest.execute();

            spotifyApi.setAccessToken(credentials.getAccessToken());
            spotifyApi.setRefreshToken(credentials.getRefreshToken());
            tokenStore.save(credentials.getAccessToken(), credentials.getRefreshToken(),
                    credentials.getExpiresIn());

            Log.info("Spotify", "Spotify OAuth completed. Access token expires in " + credentials.getExpiresIn() + " seconds.");
            return true;
        } catch (IOException | SpotifyWebApiException | ParseException e) {
            Log.error("Spotify", "Error exchanging Spotify authorization code: " + e.getMessage());
            return false;
        }
    }

    /**
     * Refreshes the access token using the stored refresh token.
     * Spotify access tokens expire after 1 hour. Successful refreshes are
     * persisted so the session survives reboots; a rejected refresh token
     * clears the store and forces a new QR login.
     *
     * @return True if token was refreshed successfully, false otherwise.
     */
    public boolean refreshAccessToken() {
        if (spotifyApi.getRefreshToken() == null || spotifyApi.getRefreshToken().isEmpty()) {
            Log.error("Spotify", "No refresh token available. User re-authentication required.");
            return false;
        }
        try {
            AuthorizationCodeRefreshRequest refreshRequest =
                    spotifyApi.authorizationCodeRefresh().build();

            AuthorizationCodeCredentials credentials = refreshRequest.execute();

            spotifyApi.setAccessToken(credentials.getAccessToken());
            if (credentials.getRefreshToken() != null) {
                spotifyApi.setRefreshToken(credentials.getRefreshToken());
            }
            tokenStore.save(credentials.getAccessToken(), credentials.getRefreshToken(),
                    credentials.getExpiresIn());
            Log.info("Spotify", "Spotify access token refreshed successfully.");
            return true;
        } catch (IOException | SpotifyWebApiException | ParseException e) {
            String message = e.getMessage() != null ? e.getMessage() : "";
            if (message.contains("400") || message.contains("invalid_grant")) {
                // Refresh token revoked/expired: wipe stale credentials so the
                // QR overlay comes back on the next startup.
                Log.error("Spotify", "Spotify refresh token rejected; clearing stored session.");
                tokenStore.clear();
                spotifyApi.setAccessToken(null);
                spotifyApi.setRefreshToken(null);
            } else {
                Log.error("Spotify", "Error refreshing Spotify access token: " + message);
            }
            return false;
        }
    }

    public String getAccessToken() {
        return spotifyApi.getAccessToken();
    }

    /**
     * Retrieves full playback state context including connected active device info from Spotify API.
     *
     * @return CurrentlyPlayingContext, or null on error or no active playback.
     */
    public CurrentlyPlayingContext getPlaybackContext() {
        if (spotifyApi.getAccessToken() == null || spotifyApi.getAccessToken().isEmpty()) {
            return null;
        }
        try {
            GetInformationAboutUsersCurrentPlaybackRequest request =
                    spotifyApi.getInformationAboutUsersCurrentPlayback().build();

            return request.execute();
        } catch (SpotifyWebApiException e) {
            if (e.getMessage() != null && e.getMessage().contains("401")) {
                Log.info("Spotify", "Spotify token expired. Refreshing...");
                if (refreshAccessToken()) {
                    return getPlaybackContext();
                }
            }
            Log.error("Spotify", "Spotify API error: " + e.getMessage());
            return null;
        } catch (IOException | ParseException e) {
            Log.error("Spotify", "Error fetching Spotify playback context: " + e.getMessage());
            return null;
        }
    }

    /**
     * Retrieves raw CurrentlyPlaying object from Spotify API.
     * Automatically attempts to refresh the access token if an expired token error (401) is encountered.
     *
     * @return CurrentlyPlaying object, or null if no track is playing or on error.
     */
    public CurrentlyPlaying getCurrentlyPlaying() {
        if (spotifyApi.getAccessToken() == null || spotifyApi.getAccessToken().isEmpty()) {
            return null;
        }
        try {
            GetUsersCurrentlyPlayingTrackRequest request =
                    spotifyApi.getUsersCurrentlyPlayingTrack().build();

            return request.execute();
        } catch (SpotifyWebApiException e) {
            if (e.getMessage() != null && e.getMessage().contains("401")) {
                Log.info("Spotify", "Spotify token expired. Refreshing...");
                if (refreshAccessToken()) {
                    return getCurrentlyPlaying();
                }
            }
            Log.error("Spotify", "Spotify API error: " + e.getMessage());
            return null;
        } catch (IOException | ParseException e) {
            Log.error("Spotify", "Error fetching currently playing track: " + e.getMessage());
            return null;
        }
    }

    /**
     * Fetches current playback and returns a structured {@link SpotifyTrackData} object containing
     * song name, artist name(s), album cover images, and Raspberry Pi speaker device info.
     *
     * @return SpotifyTrackData instance, or null if nothing is playing or not authenticated.
     */
    public SpotifyTrackData getCurrentTrackData() {
        CurrentlyPlayingContext context = getPlaybackContext();
        if (context != null && context.getItem() != null) {
            return extractTrackData(context);
        }

        CurrentlyPlaying currentlyPlaying = getCurrentlyPlaying();
        return extractTrackData(currentlyPlaying);
    }

    /**
     * Extracts structured {@link SpotifyTrackData} from a {@link CurrentlyPlayingContext} object.
     *
     * @param context Playback context from Spotify API.
     * @return Parsed SpotifyTrackData, or null if context is null or no item is active.
     */
    public SpotifyTrackData extractTrackData(CurrentlyPlayingContext context) {
        if (context == null || context.getItem() == null) {
            return null;
        }

        String songName = getSongNameFromItem(context.getItem());
        String artistName = getArtistNameFromItem(context.getItem());
        List<String> artists = getArtistNamesFromItem(context.getItem());
        String albumName = getAlbumNameFromItem(context.getItem());
        String coverUrl = getAlbumCoverUrlFromItem(context.getItem());
        List<String> coverUrls = getAlbumCoverUrlsFromItem(context.getItem());
        boolean isPlaying = context.getIs_playing() != null && context.getIs_playing();

        String deviceName = "Raspberry Pi Speaker";
        if (context.getDevice() != null && context.getDevice().getName() != null && !context.getDevice().getName().isBlank()) {
            deviceName = context.getDevice().getName();
        }

        return new SpotifyTrackData(
                songName,
                artistName,
                artists,
                albumName,
                coverUrl,
                coverUrls,
                isPlaying,
                deviceName
        );
    }

    /**
     * Extracts structured {@link SpotifyTrackData} from a {@link CurrentlyPlaying} object.
     *
     * @param currentlyPlaying The currently playing response from Spotify API.
     * @return Parsed SpotifyTrackData, or null if currentlyPlaying is null or no item is active.
     */
    public SpotifyTrackData extractTrackData(CurrentlyPlaying currentlyPlaying) {
        if (currentlyPlaying == null || currentlyPlaying.getItem() == null) {
            return null;
        }

        String songName = getSongNameFromItem(currentlyPlaying.getItem());
        String artistName = getArtistNameFromItem(currentlyPlaying.getItem());
        List<String> artists = getArtistNamesFromItem(currentlyPlaying.getItem());
        String albumName = getAlbumNameFromItem(currentlyPlaying.getItem());
        String coverUrl = getAlbumCoverUrlFromItem(currentlyPlaying.getItem());
        List<String> coverUrls = getAlbumCoverUrlsFromItem(currentlyPlaying.getItem());
        boolean isPlaying = currentlyPlaying.getIs_playing() != null && currentlyPlaying.getIs_playing();

        return new SpotifyTrackData(
                songName,
                artistName,
                artists,
                albumName,
                coverUrl,
                coverUrls,
                isPlaying,
                "Raspberry Pi Speaker"
        );
    }

    // =========================================================================
    // SONG NAME FUNCTIONS
    // =========================================================================

    /**
     * Extracts the song / track name from a {@link CurrentlyPlaying} object.
     *
     * @param currentlyPlaying Currently playing track object.
     * @return Song name, episode name, or empty string if not available.
     */
    public String getSongName(CurrentlyPlaying currentlyPlaying) {
        if (currentlyPlaying == null) {
            return "";
        }
        return getSongNameFromItem(currentlyPlaying.getItem());
    }

    /**
     * Extracts the song / track name from a playlist item (Track or Episode).
     *
     * @param item Track or Episode item.
     * @return Song name or empty string.
     */
    public String getSongNameFromItem(IPlaylistItem item) {
        if (item == null) {
            return "";
        }
        if (item instanceof Track track) {
            return track.getName() != null ? track.getName() : "Unknown Title";
        } else if (item instanceof Episode episode) {
            return episode.getName() != null ? episode.getName() : "Unknown Episode";
        }
        return "";
    }

    /**
     * Fetches the currently playing track from Spotify and returns its song name.
     *
     * @return Current song name, or empty string if nothing is playing.
     */
    public String getCurrentSongName() {
        return getSongName(getCurrentlyPlaying());
    }

    // =========================================================================
    // ARTIST NAME FUNCTIONS
    // =========================================================================

    /**
     * Extracts the formatted artist name(s) from a {@link CurrentlyPlaying} object.
     *
     * @param currentlyPlaying Currently playing track object.
     * @return Formatted artist name(s), or empty string if not available.
     */
    public String getArtistName(CurrentlyPlaying currentlyPlaying) {
        if (currentlyPlaying == null) {
            return "";
        }
        return getArtistNameFromItem(currentlyPlaying.getItem());
    }

    /**
     * Extracts the formatted artist name(s) from a playlist item.
     *
     * @param item Track or Episode item.
     * @return Formatted artist name(s).
     */
    public String getArtistNameFromItem(IPlaylistItem item) {
        List<String> artists = getArtistNamesFromItem(item);
        if (artists.isEmpty()) {
            return "";
        }
        return String.join(", ", artists);
    }

    /**
     * Extracts the primary (first) artist name from a {@link CurrentlyPlaying} object.
     *
     * @param currentlyPlaying Currently playing track object.
     * @return Primary artist name, or empty string if not available.
     */
    public String getPrimaryArtistName(CurrentlyPlaying currentlyPlaying) {
        List<String> artists = getArtistNames(currentlyPlaying);
        return artists.isEmpty() ? "" : artists.get(0);
    }

    /**
     * Extracts the list of all artist names from a {@link CurrentlyPlaying} object.
     *
     * @param currentlyPlaying Currently playing track object.
     * @return List of artist names, or empty list if not available.
     */
    public List<String> getArtistNames(CurrentlyPlaying currentlyPlaying) {
        if (currentlyPlaying == null) {
            return Collections.emptyList();
        }
        return getArtistNamesFromItem(currentlyPlaying.getItem());
    }

    /**
     * Extracts the list of all artist names from a playlist item.
     *
     * @param item Track or Episode item.
     * @return List of artist names.
     */
    public List<String> getArtistNamesFromItem(IPlaylistItem item) {
        if (item == null) {
            return Collections.emptyList();
        }

        if (item instanceof Track track) {
            if (track.getArtists() == null || track.getArtists().length == 0) {
                return List.of("Unknown Artist");
            }
            return Arrays.stream(track.getArtists())
                    .map(ArtistSimplified::getName)
                    .filter(name -> name != null && !name.isBlank())
                    .collect(Collectors.toList());
        } else if (item instanceof Episode episode) {
            if (episode.getShow() != null && episode.getShow().getName() != null) {
                return List.of(episode.getShow().getName());
            }
            return List.of("Unknown Podcast");
        }

        return Collections.emptyList();
    }

    /**
     * Fetches the currently playing track from Spotify and returns its formatted artist name(s).
     *
     * @return Current artist name(s), or empty string if nothing is playing.
     */
    public String getCurrentArtistName() {
        return getArtistName(getCurrentlyPlaying());
    }

    // =========================================================================
    // ALBUM & COVER ART IMAGE FUNCTIONS
    // =========================================================================

    /**
     * Extracts the best album cover art image URL for display on the smart assistant screen.
     *
     * @param currentlyPlaying Currently playing track object.
     * @return Image URL string, or empty string if no image is available.
     */
    public String getAlbumCoverUrl(CurrentlyPlaying currentlyPlaying) {
        if (currentlyPlaying == null) {
            return "";
        }
        return getAlbumCoverUrlFromItem(currentlyPlaying.getItem());
    }

    /**
     * Extracts album cover image URL from a playlist item.
     *
     * @param item Track or Episode item.
     * @return Image URL string.
     */
    public String getAlbumCoverUrlFromItem(IPlaylistItem item) {
        return getAlbumCoverUrlBySizeFromItem(item, ImageSizePreference.MEDIUM);
    }

    /**
     * Extracts an album cover image URL based on the requested size preference.
     *
     * @param currentlyPlaying Currently playing track object.
     * @param preference       Desired image size preference (LARGE, MEDIUM, SMALL).
     * @return Image URL string, or empty string if not available.
     */
    public String getAlbumCoverUrlBySize(CurrentlyPlaying currentlyPlaying, ImageSizePreference preference) {
        if (currentlyPlaying == null) {
            return "";
        }
        return getAlbumCoverUrlBySizeFromItem(currentlyPlaying.getItem(), preference);
    }

    /**
     * Extracts an album cover image URL from a playlist item by size preference.
     *
     * @param item       Track or Episode item.
     * @param preference Size preference.
     * @return Image URL string.
     */
    public String getAlbumCoverUrlBySizeFromItem(IPlaylistItem item, ImageSizePreference preference) {
        Image[] images = getRawImages(item);
        if (images == null || images.length == 0) {
            return "";
        }

        if (preference == null) {
            preference = ImageSizePreference.MEDIUM;
        }

        switch (preference) {
            case LARGE:
                return images[0].getUrl() != null ? images[0].getUrl() : "";
            case SMALL:
                return images[images.length - 1].getUrl() != null ? images[images.length - 1].getUrl() : "";
            case MEDIUM:
            default:
                if (images.length >= 2 && images[1].getUrl() != null) {
                    return images[1].getUrl();
                }
                return images[0].getUrl() != null ? images[0].getUrl() : "";
        }
    }

    /**
     * Extracts all available cover art image URLs from a {@link CurrentlyPlaying} object.
     *
     * @param currentlyPlaying Currently playing track object.
     * @return List of image URLs, or empty list if not available.
     */
    public List<String> getAlbumCoverUrls(CurrentlyPlaying currentlyPlaying) {
        if (currentlyPlaying == null) {
            return Collections.emptyList();
        }
        return getAlbumCoverUrlsFromItem(currentlyPlaying.getItem());
    }

    /**
     * Extracts all available cover art image URLs from a playlist item.
     *
     * @param item Track or Episode item.
     * @return List of image URLs.
     */
    public List<String> getAlbumCoverUrlsFromItem(IPlaylistItem item) {
        Image[] images = getRawImages(item);
        if (images == null || images.length == 0) {
            return Collections.emptyList();
        }

        List<String> urls = new ArrayList<>();
        for (Image image : images) {
            if (image != null && image.getUrl() != null && !image.getUrl().isBlank()) {
                urls.add(image.getUrl());
            }
        }
        return urls;
    }

    /**
     * Fetches the currently playing track from Spotify and returns its album cover art URL.
     *
     * @return Current album cover art URL, or empty string if nothing is playing.
     */
    public String getCurrentAlbumCoverUrl() {
        return getAlbumCoverUrl(getCurrentlyPlaying());
    }

    /**
     * Extracts the album name from a {@link CurrentlyPlaying} object.
     *
     * @param currentlyPlaying Currently playing track object.
     * @return Album name, or empty string if not available.
     */
    public String getAlbumName(CurrentlyPlaying currentlyPlaying) {
        if (currentlyPlaying == null) {
            return "";
        }
        return getAlbumNameFromItem(currentlyPlaying.getItem());
    }

    /**
     * Extracts the album name from a playlist item.
     *
     * @param item Track or Episode item.
     * @return Album name, or empty string if not available.
     */
    public String getAlbumNameFromItem(IPlaylistItem item) {
        if (item == null) {
            return "";
        }

        if (item instanceof Track track) {
            AlbumSimplified album = track.getAlbum();
            return album != null && album.getName() != null ? album.getName() : "";
        } else if (item instanceof Episode episode) {
            return episode.getShow() != null && episode.getShow().getName() != null ? episode.getShow().getName() : "";
        }

        return "";
    }

    /**
     * Helper method to extract raw Image array from either Track album or Episode.
     */
    private Image[] getRawImages(IPlaylistItem item) {
        if (item == null) {
            return null;
        }

        if (item instanceof Track track) {
            AlbumSimplified album = track.getAlbum();
            if (album != null && album.getImages() != null) {
                return album.getImages();
            }
        } else if (item instanceof Episode episode) {
            if (episode.getImages() != null) {
                return episode.getImages();
            }
        }

        return null;
    }
}