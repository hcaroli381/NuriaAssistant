package com.example.nuriaassistant.models;

import java.util.List;

/**
 * Immutable data carrier for Spotify track and playback metadata.
 */
public record SpotifyTrackData(
        String title,
        String artist,
        List<String> artists,
        String album,
        String coverUrl,
        List<String> coverUrls,
        boolean isPlaying,
        String deviceName
) {
    public SpotifyTrackData {
        artists = artists != null ? List.copyOf(artists) : List.of();
        coverUrls = coverUrls != null ? List.copyOf(coverUrls) : List.of();
        if (deviceName == null || deviceName.isBlank()) {
            deviceName = "Raspberry Pi Speaker";
        }
    }
}
