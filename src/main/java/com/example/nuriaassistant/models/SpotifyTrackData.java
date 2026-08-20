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
        int progressMs,
        int durationMs
) {
    public SpotifyTrackData {
        artists = artists != null ? List.copyOf(artists) : List.of();
        coverUrls = coverUrls != null ? List.copyOf(coverUrls) : List.of();
    }

    /**
     * Formats current progress and total track duration as "MM:SS / MM:SS".
     *
     * @return Formatted progress string, or empty string if duration is invalid.
     */
    public String getFormattedProgress() {
        if (durationMs <= 0) {
            return "";
        }
        int progMin = progressMs / 60000;
        int progSec = (progressMs % 60000) / 1000;
        int durMin = durationMs / 60000;
        int durSec = (durationMs % 60000) / 1000;
        return String.format("%d:%02d / %d:%02d", progMin, progSec, durMin, durSec);
    }
}
