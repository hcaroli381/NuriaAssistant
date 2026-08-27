package com.example.nuriaassistant.ui;

/**
 * Pure display mappings for the weather row (no scene-graph state).
 */
public final class WeatherUi {

    private WeatherUi() {
    }

    /** Returns the emoji icon for an OpenWeather description (e.g. "clear" → ☀️). */
    public static String iconFor(String description) {
        if (description == null) {
            return "🌡️";
        }
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

    /** Capitalizes the first letter and lowercases the rest ("Clear sky" → "Clear sky"). */
    public static String capitalize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return Character.toUpperCase(text.charAt(0)) + text.substring(1).toLowerCase();
    }
}
