package com.example.nuriaassistant.spotify;

import com.example.nuriaassistant.models.SpotifyTrackData;
import org.apache.hc.core5.http.NameValuePair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.michaelthelin.spotify.model_objects.miscellaneous.CurrentlyPlaying;
import se.michaelthelin.spotify.model_objects.specification.AlbumSimplified;
import se.michaelthelin.spotify.model_objects.specification.ArtistSimplified;
import se.michaelthelin.spotify.model_objects.specification.Episode;
import se.michaelthelin.spotify.model_objects.specification.Image;
import se.michaelthelin.spotify.model_objects.specification.ShowSimplified;
import se.michaelthelin.spotify.model_objects.specification.Track;
import se.michaelthelin.spotify.requests.authorization.authorization_code.AuthorizationCodeRequest;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

public class SpotifyServiceTest {

    private SpotifyService spotifyService;

    @BeforeEach
    void setUp() {
        spotifyService = new SpotifyService("test-client-id", "test-client-secret", "http://127.0.0.1:8888/callback");
    }

    @Test
    void testExtractTrackDataWithSingleArtistAndImages() {
        Image imgLarge = new Image.Builder().setUrl("https://img.spotify.com/640").setHeight(640).setWidth(640).build();
        Image imgMedium = new Image.Builder().setUrl("https://img.spotify.com/300").setHeight(300).setWidth(300).build();
        Image imgSmall = new Image.Builder().setUrl("https://img.spotify.com/64").setHeight(64).setWidth(64).build();

        AlbumSimplified album = new AlbumSimplified.Builder()
                .setName("A Night at the Opera")
                .setImages(imgLarge, imgMedium, imgSmall)
                .build();

        ArtistSimplified artist = new ArtistSimplified.Builder()
                .setName("Queen")
                .build();

        Track track = new Track.Builder()
                .setName("Bohemian Rhapsody")
                .setArtists(artist)
                .setAlbum(album)
                .setDurationMs(354000)
                .build();

        CurrentlyPlaying currentlyPlaying = new CurrentlyPlaying.Builder()
                .setItem(track)
                .setIs_playing(true)
                .setProgress_ms(65000)
                .build();

        // 1. Test Song Name
        String songName = spotifyService.getSongName(currentlyPlaying);
        assertEquals("Bohemian Rhapsody", songName);

        // 2. Test Artist Name(s)
        String artistName = spotifyService.getArtistName(currentlyPlaying);
        assertEquals("Queen", artistName);
        assertEquals("Queen", spotifyService.getPrimaryArtistName(currentlyPlaying));
        assertEquals(List.of("Queen"), spotifyService.getArtistNames(currentlyPlaying));

        // 3. Test Album Cover Images
        String mediumCover = spotifyService.getAlbumCoverUrl(currentlyPlaying);
        assertEquals("https://img.spotify.com/300", mediumCover);

        String largeCover = spotifyService.getAlbumCoverUrlBySize(currentlyPlaying, SpotifyService.ImageSizePreference.LARGE);
        assertEquals("https://img.spotify.com/640", largeCover);

        String smallCover = spotifyService.getAlbumCoverUrlBySize(currentlyPlaying, SpotifyService.ImageSizePreference.SMALL);
        assertEquals("https://img.spotify.com/64", smallCover);

        List<String> allUrls = spotifyService.getAlbumCoverUrls(currentlyPlaying);
        assertEquals(3, allUrls.size());
        assertEquals("https://img.spotify.com/640", allUrls.get(0));
        assertEquals("https://img.spotify.com/300", allUrls.get(1));
        assertEquals("https://img.spotify.com/64", allUrls.get(2));

        // 4. Test Album Name
        assertEquals("A Night at the Opera", spotifyService.getAlbumName(currentlyPlaying));

        // 5. Test Full Track Data Extraction
        SpotifyTrackData data = spotifyService.extractTrackData(currentlyPlaying);
        assertNotNull(data);
        assertEquals("Bohemian Rhapsody", data.title());
        assertEquals("Queen", data.artist());
        assertEquals("A Night at the Opera", data.album());
        assertEquals("https://img.spotify.com/300", data.coverUrl());
        assertTrue(data.isPlaying());
        assertEquals("Raspberry Pi Speaker", data.deviceName());
    }

    @Test
    void testExtractTrackDataWithMultipleArtists() {
        ArtistSimplified artist1 = new ArtistSimplified.Builder().setName("Daft Punk").build();
        ArtistSimplified artist2 = new ArtistSimplified.Builder().setName("Pharrell Williams").build();
        ArtistSimplified artist3 = new ArtistSimplified.Builder().setName("Nile Rodgers").build();

        Track track = new Track.Builder()
                .setName("Get Lucky")
                .setArtists(artist1, artist2, artist3)
                .setDurationMs(248000)
                .build();

        CurrentlyPlaying currentlyPlaying = new CurrentlyPlaying.Builder()
                .setItem(track)
                .setIs_playing(true)
                .setProgress_ms(120000)
                .build();

        assertEquals("Get Lucky", spotifyService.getSongName(currentlyPlaying));
        assertEquals("Daft Punk, Pharrell Williams, Nile Rodgers", spotifyService.getArtistName(currentlyPlaying));
        assertEquals("Daft Punk", spotifyService.getPrimaryArtistName(currentlyPlaying));
        assertEquals(List.of("Daft Punk", "Pharrell Williams", "Nile Rodgers"), spotifyService.getArtistNames(currentlyPlaying));
    }

    @Test
    void testExtractTrackDataWithPodcastEpisode() {
        Image img = new Image.Builder().setUrl("https://img.spotify.com/podcast").build();
        ShowSimplified show = new ShowSimplified.Builder().setName("The Daily").build();

        Episode episode = new Episode.Builder()
                .setName("Episode 100")
                .setShow(show)
                .setImages(img)
                .setDurationMs(1800000)
                .build();

        CurrentlyPlaying currentlyPlaying = new CurrentlyPlaying.Builder()
                .setItem(episode)
                .setIs_playing(true)
                .setProgress_ms(300000)
                .build();

        assertEquals("Episode 100", spotifyService.getSongName(currentlyPlaying));
        assertEquals("The Daily", spotifyService.getArtistName(currentlyPlaying));
        assertEquals("https://img.spotify.com/podcast", spotifyService.getAlbumCoverUrl(currentlyPlaying));
        assertEquals("The Daily", spotifyService.getAlbumName(currentlyPlaying));
    }

    @Test
    void testAuthorizationUriUsesConfiguredRedirectByDefault() throws Exception {
        String url = spotifyService.getAuthorizationUri();

        assertTrue(url.startsWith("https://accounts.spotify.com"), url);
        assertTrue(url.contains("/authorize?"), url);
        assertTrue(url.contains("client_id=test-client-id"), url);
        assertTrue(url.contains("response_type=code"), url);
        assertTrue(url.contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A8888%2Fcallback"), url);
        assertFalse(url.contains("state="), "no state unless the phone flow asks for it");
    }

    @Test
    void testAuthorizationUriCarriesRelayRedirectAndPairingState() throws Exception {
        String relay = "https://hcaroli381.github.io/NuriaAssistant/spotify-callback.html";
        String state = new SpotifyPairing.State("tok123", "192.168.1.50", 8888).encode();

        String url = spotifyService.getAuthorizationUri(relay, state);

        assertTrue(url.startsWith("https://accounts.spotify.com"), url);
        assertTrue(url.contains("/authorize?"), url);
        assertTrue(url.contains("redirect_uri=" + URLEncoder.encode(relay, StandardCharsets.UTF_8)), url);
        assertTrue(url.contains("state=" + state), url);
        assertFalse(url.contains("127.0.0.1"), "the loopback URI must not leak into the phone flow: " + url);
    }

    /**
     * Spotify ties the authorization code to the redirect URI it was issued for
     * and rejects a mismatched exchange, so the code must be redeemed against
     * the very URI the QR authorized with — never the client's loopback default.
     */
    @Test
    void testCodeExchangeReusesTheRedirectUriTheAuthorizationAskedFor() throws Exception {
        String relay = "https://hcaroli381.github.io/NuriaAssistant/spotify-callback.html";

        assertNull(spotifyService.redirectUriForCodeExchange(),
                "an untouched service redeems against its configured URI");

        spotifyService.getAuthorizationUri(relay, "tok~192.168.1.50~8888");
        assertEquals(relay, spotifyService.redirectUriForCodeExchange());
    }

    @Test
    void testLoopbackAuthorizationKeepsUsingTheConfiguredRedirectUri() throws Exception {
        spotifyService.getAuthorizationUri();

        assertNull(spotifyService.redirectUriForCodeExchange(),
                "desktop flow must keep redeeming against SPOTIFY_REDIRECT_URI");
    }

    /** The wire payload, not just the remembered value. */
    @Test
    void testCodeExchangeBodyCarriesThePhoneFlowRedirectUri() throws Exception {
        String relay = "https://hcaroli381.github.io/NuriaAssistant/spotify-callback.html";
        spotifyService.getAuthorizationUri(relay, "tok~192.168.1.50~8888");

        Map<String, String> body = bodyOf(spotifyService.buildCodeExchange("CODE42"));

        assertEquals(relay, body.get("redirect_uri"));
        assertEquals("CODE42", body.get("code"));
        assertEquals("authorization_code", body.get("grant_type"));
        assertFalse(body.get("redirect_uri").contains("127.0.0.1"),
                "the loopback URI would break every phone login");
    }

    @Test
    void testCodeExchangeBodyFallsBackToTheConfiguredRedirectUri() throws Exception {
        Map<String, String> body = bodyOf(spotifyService.buildCodeExchange("CODE42"));

        assertEquals("http://127.0.0.1:8888/callback", body.get("redirect_uri"));
    }

    private static Map<String, String> bodyOf(AuthorizationCodeRequest request) {
        return request.getBodyParameters().stream()
                .collect(Collectors.toMap(NameValuePair::getName, NameValuePair::getValue));
    }

    @Test
    void testNullAndEmptyHandling() {
        assertNull(spotifyService.extractTrackData((CurrentlyPlaying) null));
        assertEquals("", spotifyService.getSongName(null));
        assertEquals("", spotifyService.getArtistName(null));
        assertEquals("", spotifyService.getAlbumCoverUrl(null));
        assertEquals("", spotifyService.getAlbumName(null));
        assertTrue(spotifyService.getArtistNames(null).isEmpty());
        assertTrue(spotifyService.getAlbumCoverUrls(null).isEmpty());

        CurrentlyPlaying emptyPlaying = new CurrentlyPlaying.Builder().build();
        assertNull(spotifyService.extractTrackData(emptyPlaying));
        assertEquals("", spotifyService.getSongName(emptyPlaying));
        assertEquals("", spotifyService.getArtistName(emptyPlaying));
        assertEquals("", spotifyService.getAlbumCoverUrl(emptyPlaying));
        assertEquals("", spotifyService.getAlbumName(emptyPlaying));
    }

    @Test
    void testSpotifyTrackDataSpeakerDefault() {
        SpotifyTrackData dataWithNullDevice = new SpotifyTrackData(
                "Song", "Artist", List.of("Artist"), "Album",
                "http://example.com/art.jpg", List.of("http://example.com/art.jpg"),
                true, null
        );
        assertEquals("Raspberry Pi Speaker", dataWithNullDevice.deviceName());

        SpotifyTrackData dataWithCustomDevice = new SpotifyTrackData(
                "Song", "Artist", List.of("Artist"), "Album",
                "http://example.com/art.jpg", List.of("http://example.com/art.jpg"),
                true, "Living Room Pi"
        );
        assertEquals("Living Room Pi", dataWithCustomDevice.deviceName());
    }
}
