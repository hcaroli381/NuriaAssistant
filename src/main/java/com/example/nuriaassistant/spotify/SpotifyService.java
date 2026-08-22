package com.example.nuriaassistant.spotify;

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
import java.util.List;
import java.util.function.Consumer;
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
                    response = "<!DOCTYPE html><html><head><title>Alpha Assistant - Spotify Connected</title></head>"
                            + "<body style='font-family: Arial, sans-serif; text-align: center; padding-top: 50px; background: #121212; color: #1DB954;'>"
                            + "<h1>✓ Spotify Connected Successfully!</h1>"
                            + "<p style='color: #FFFFFF; font-size: 18px;'>You can now close this window and return to Alpha Assistant.</p>"
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
                System.out.println("Spotify token expired. Refreshing...");
                if (refreshAccessToken()) {
                    return getPlaybackContext();
                }
            }
            System.err.println("Spotify API error: " + e.getMessage());
            return null;
        } catch (IOException | ParseException e) {
            System.err.println("Error fetching Spotify playback context: " + e.getMessage());
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