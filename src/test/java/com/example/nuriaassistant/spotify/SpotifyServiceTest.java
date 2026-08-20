package com.example.nuriaassistant.spotify;

import com.example.nuriaassistant.models.SpotifyTrackData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.michaelthelin.spotify.model_objects.miscellaneous.CurrentlyPlaying;
import se.michaelthelin.spotify.model_objects.specification.AlbumSimplified;
import se.michaelthelin.spotify.model_objects.specification.ArtistSimplified;
import se.michaelthelin.spotify.model_objects.specification.Episode;
import se.michaelthelin.spotify.model_objects.specification.Image;
import se.michaelthelin.spotify.model_objects.specification.ShowSimplified;
import se.michaelthelin.spotify.model_objects.specification.Track;

import java.util.List;

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
        assertEquals(65000, data.progressMs());
        assertEquals(354000, data.durationMs());
        assertEquals("1:05 / 5:54", data.getFormattedProgress());
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
    void testNullAndEmptyHandling() {
        assertNull(spotifyService.extractTrackData(null));
        assertEquals("", spotifyService.getSongName(null));
        assertEquals("", spotifyService.getArtistName(null));
        assertEquals("", spotifyService.getAlbumCoverUrl(null));
        assertEquals("", spotifyService.getAlbumName(null));
        assertEquals(0, spotifyService.getDurationMs(null));
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
    void testSpotifyTrackDataFormattedProgress() {
        SpotifyTrackData data = new SpotifyTrackData(
                "Song", "Artist", List.of("Artist"), "Album",
                "http://example.com/art.jpg", List.of("http://example.com/art.jpg"),
                true, 75000, 215000
        );

        assertEquals("1:15 / 3:35", data.getFormattedProgress());

        SpotifyTrackData invalidDuration = new SpotifyTrackData(
                "Song", "Artist", List.of("Artist"), "Album",
                null, null, true, 0, 0
        );
        assertEquals("", invalidDuration.getFormattedProgress());
    }
}
