package com.example.nuriaassistant.spotify;

import com.example.nuriaassistant.models.SpotifyTrackData;
import com.sun.net.httpserver.HttpServer;
import org.apache.hc.core5.http.ParseException;
import se.michaelthelin.spotify.SpotifyApi;
import se.michaelthelin.spotify.exceptions.SpotifyWebApiException;
import se.michaelthelin.spotify.model_objects.IPlaylistItem;
import se.michaelthelin.spotify.model_objects.credentials.AuthorizationCodeCredentials;
import se.michaelthelin.spotify.model_objects.miscellaneous.CurrentlyPlaying;
import se.michaelthelin.spotify.model_objects.specification.AlbumSimplified;
import se.michaelthelin.spotify.model_objects.specification.ArtistSimplified;
import se.michaelthelin.spotify.model_objects.specification.Episode;
import se.michaelthelin.spotify.model_objects.specification.Image;
import se.michaelthelin.spotify.model_objects.specification.Track;
import se.michaelthelin.spotify.requests.authorization.authorization_code.AuthorizationCodeRefreshRequest;
import se.michaelthelin.spotify.requests.authorization.authorization_code.AuthorizationCodeRequest;
import se.michaelthelin.spotify.requests.authorization.authorization_code.AuthorizationCodeUriRequest;
import se.michaelthelin.spotify.requests.data.player.GetUsersCurrentlyPlayingTrackRequest;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Service for interacting with Spotify Web API, retrieving currently playing
 * track metadata, song images, song names, artist names, and managing OAuth authentication.
 */
public class SpotifyService {

    public enum ImageSizePreference {
        LARGE,   // Typically 640x640
        MEDIUM,  // Typically 300x300 (optimal for 1024x600 screen)
        SMALL    // Typically 64x64
    }

    private final SpotifyApi spotifyApi;
    private HttpServer authCallbackServer;

    public SpotifyService(String clientId, String clientSecret, String redirectUri) {
        this.spotifyApi = new SpotifyApi.Builder()
                .setClientId(clientId)
                .setClientSecret(clientSecret)
                .setRedirectUri(URI.create(redirectUri))
                .build();
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
        AuthorizationCodeUriRequest authRequest = spotifyApi.authorizationCodeUri()
                .scope("user-read-currently-playing,user-read-playback-state")
                .show_dialog(true)
                .build();

        URI uri = authRequest.execute();
        return uri.toString();
    }

    /**
     * Starts an embedded HTTP server to automatically receive the OAuth callback code on the specified port.
     *
     * @param onCodeReceived Callback invoked when the authorization code is received.
     * @param port           Port to listen on (e.g. 8888).
     */
    public void startAuthCallbackServer(Consumer<String> onCodeReceived, int port) {
        if (authCallbackServer != null) {
            return;
        }
        try {
            authCallbackServer = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
            authCallbackServer.createContext("/callback", exchange -> {
                String query = exchange.getRequestURI().getQuery();
                String code = null;
                if (query != null) {
                    for (String param : query.split("&")) {
                        String[] pair = param.split("=");
                        if (pair.length == 2 && "code".equals(pair[0])) {
                            code = java.net.URLDecoder.decode(pair[1], java.nio.charset.StandardCharsets.UTF_8);
                            break;
                        }
                    }
                }

                String response;
                if (code != null) {
                    response = "<!DOCTYPE html><html><head><title>Nuria Assistant - Spotify Connected</title></head>"
                            + "<body style='font-family: Arial, sans-serif; text-align: center; padding-top: 50px; background: #121212; color: #1DB954;'>"
                            + "<h1>✓ Spotify Connected Successfully!</h1>"
                            + "<p style='color: #FFFFFF; font-size: 18px;'>You can now close this window and return to Nuria Assistant.</p>"
                            + "</body></html>";
                    exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                    exchange.sendResponseHeaders(200, response.getBytes().length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(response.getBytes());
                    }
                    if (onCodeReceived != null) {
                        onCodeReceived.accept(code);
                    }
                } else {
                    response = "<!DOCTYPE html><html><body style='font-family: Arial, sans-serif; text-align: center; padding-top: 50px;'>"
                            + "<h1>Authentication Failed</h1><p>No authorization code received.</p></body></html>";
                    exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                    exchange.sendResponseHeaders(400, response.getBytes().length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(response.getBytes());
                    }
                }
            });
            authCallbackServer.setExecutor(null);
            authCallbackServer.start();
            System.out.println("Spotify OAuth callback server started on port " + port);
        } catch (IOException e) {
            System.err.println("Failed to start Spotify OAuth callback server on port " + port + ": " + e.getMessage());
        }
    }

    /**
     * Stops the OAuth callback server if running.
     */
    public void stopAuthCallbackServer() {
        if (authCallbackServer != null) {
            authCallbackServer.stop(0);
            authCallbackServer = null;
            System.out.println("Spotify OAuth callback server stopped.");
        }
    }

    /**
     * Exchanges the authorization code received from OAuth callback for access and refresh tokens.
     *
     * @param authorizationCode Code received from Spotify OAuth redirect.
     * @return True if tokens were successfully obtained and saved, false otherwise.
     */
    public boolean exchangeCodeForTokens(String authorizationCode) {
        try {
            AuthorizationCodeRequest authorizationCodeRequest =
                    spotifyApi.authorizationCode(authorizationCode).build();

            AuthorizationCodeCredentials credentials = authorizationCodeRequest.execute();

            spotifyApi.setAccessToken(credentials.getAccessToken());
            spotifyApi.setRefreshToken(credentials.getRefreshToken());

            System.out.println("Spotify OAuth completed. Access token expires in " + credentials.getExpiresIn() + " seconds.");
            return true;
        } catch (IOException | SpotifyWebApiException | ParseException e) {
            System.err.println("Error exchanging Spotify authorization code: " + e.getMessage());
            return false;
        }
    }

    /**
     * Refreshes the access token using the stored refresh token.
     * Spotify access tokens expire after 1 hour.
     *
     * @return True if token was refreshed successfully, false otherwise.
     */
    public boolean refreshAccessToken() {
        if (spotifyApi.getRefreshToken() == null || spotifyApi.getRefreshToken().isEmpty()) {
            System.err.println("No refresh token available. User re-authentication required.");
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
            System.out.println("Spotify access token refreshed successfully.");
            return true;
        } catch (IOException | SpotifyWebApiException | ParseException e) {
            System.err.println("Error refreshing Spotify access token: " + e.getMessage());
            return false;
        }
    }

    public String getAccessToken() {
        return spotifyApi.getAccessToken();
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
            // Handle expired token (401) by refreshing once and retrying
            if (e.getMessage() != null && e.getMessage().contains("401")) {
                System.out.println("Spotify token expired. Refreshing...");
                if (refreshAccessToken()) {
                    return getCurrentlyPlaying();
                }
            }
            System.err.println("Spotify API error: " + e.getMessage());
            return null;
        } catch (IOException | ParseException e) {
            System.err.println("Error fetching currently playing track: " + e.getMessage());
            return null;
        }
    }

    /**
     * Fetches current playback and returns a structured {@link SpotifyTrackData} object containing
     * song name, artist name(s), album cover images, and playback status.
     *
     * @return SpotifyTrackData instance, or null if nothing is playing or not authenticated.
     */
    public SpotifyTrackData getCurrentTrackData() {
        CurrentlyPlaying currentlyPlaying = getCurrentlyPlaying();
        return extractTrackData(currentlyPlaying);
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

        String songName = getSongName(currentlyPlaying);
        String artistName = getArtistName(currentlyPlaying);
        List<String> artists = getArtistNames(currentlyPlaying);
        String albumName = getAlbumName(currentlyPlaying);
        String coverUrl = getAlbumCoverUrl(currentlyPlaying);
        List<String> coverUrls = getAlbumCoverUrls(currentlyPlaying);
        boolean isPlaying = currentlyPlaying.getIs_playing() != null && currentlyPlaying.getIs_playing();
        int progressMs = currentlyPlaying.getProgress_ms() != null ? currentlyPlaying.getProgress_ms() : 0;
        int durationMs = getDurationMs(currentlyPlaying);

        return new SpotifyTrackData(
                songName,
                artistName,
                artists,
                albumName,
                coverUrl,
                coverUrls,
                isPlaying,
                progressMs,
                durationMs
        );
    }

    // =========================================================================
    // SONG NAME FUNCTIONS
    // =========================================================================

    /**
     * Extracts the song / track name from a {@link CurrentlyPlaying} object.
     * Supports both musical tracks and podcast episodes.
     *
     * @param currentlyPlaying Currently playing track object.
     * @return Song name, episode name, or empty string if not available.
     */
    public String getSongName(CurrentlyPlaying currentlyPlaying) {
        if (currentlyPlaying == null || currentlyPlaying.getItem() == null) {
            return "";
        }

        IPlaylistItem item = currentlyPlaying.getItem();
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
     * If multiple artists are present, they are joined with a comma (e.g. "Daft Punk, Pharrell Williams").
     * For podcasts, returns the show / publisher name.
     *
     * @param currentlyPlaying Currently playing track object.
     * @return Formatted artist name(s), or empty string if not available.
     */
    public String getArtistName(CurrentlyPlaying currentlyPlaying) {
        List<String> artists = getArtistNames(currentlyPlaying);
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
        if (currentlyPlaying == null || currentlyPlaying.getItem() == null) {
            return Collections.emptyList();
        }

        IPlaylistItem item = currentlyPlaying.getItem();
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
     * Prefers medium-sized images (typically 300x300) to balance visual quality and RAM/CPU usage on Raspberry Pi 3.
     *
     * @param currentlyPlaying Currently playing track object.
     * @return Image URL string, or empty string if no image is available.
     */
    public String getAlbumCoverUrl(CurrentlyPlaying currentlyPlaying) {
        return getAlbumCoverUrlBySize(currentlyPlaying, ImageSizePreference.MEDIUM);
    }

    /**
     * Extracts an album cover image URL based on the requested size preference.
     *
     * @param currentlyPlaying Currently playing track object.
     * @param preference       Desired image size preference (LARGE, MEDIUM, SMALL).
     * @return Image URL string, or empty string if not available.
     */
    public String getAlbumCoverUrlBySize(CurrentlyPlaying currentlyPlaying, ImageSizePreference preference) {
        Image[] images = getRawImages(currentlyPlaying);
        if (images == null || images.length == 0) {
            return "";
        }

        if (preference == null) {
            preference = ImageSizePreference.MEDIUM;
        }

        // Spotify usually returns images sorted descending by size:
        // [0] = 640x640, [1] = 300x300, [2] = 64x64
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
        Image[] images = getRawImages(currentlyPlaying);
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
        if (currentlyPlaying == null || currentlyPlaying.getItem() == null) {
            return "";
        }

        IPlaylistItem item = currentlyPlaying.getItem();
        if (item instanceof Track track) {
            AlbumSimplified album = track.getAlbum();
            return album != null && album.getName() != null ? album.getName() : "";
        } else if (item instanceof Episode episode) {
            return episode.getShow() != null && episode.getShow().getName() != null ? episode.getShow().getName() : "";
        }

        return "";
    }

    /**
     * Extracts total track duration in milliseconds.
     *
     * @param currentlyPlaying Currently playing track object.
     * @return Duration in milliseconds, or 0 if not available.
     */
    public int getDurationMs(CurrentlyPlaying currentlyPlaying) {
        if (currentlyPlaying == null || currentlyPlaying.getItem() == null) {
            return 0;
        }

        IPlaylistItem item = currentlyPlaying.getItem();
        if (item instanceof Track track) {
            return track.getDurationMs() != null ? track.getDurationMs() : 0;
        } else if (item instanceof Episode episode) {
            return episode.getDurationMs() != null ? episode.getDurationMs() : 0;
        }

        return 0;
    }

    /**
     * Helper method to extract raw Image array from either Track album or Episode.
     */
    private Image[] getRawImages(CurrentlyPlaying currentlyPlaying) {
        if (currentlyPlaying == null || currentlyPlaying.getItem() == null) {
            return null;
        }

        IPlaylistItem item = currentlyPlaying.getItem();
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