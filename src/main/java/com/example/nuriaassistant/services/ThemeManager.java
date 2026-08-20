package com.example.nuriaassistant.services;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Service managing time/date formatting and Day/Night theme switching.
 * Automatic Dark Mode is active between 21:00 (9 PM) and 07:00 (7 AM).
 */
public class ThemeManager {

    public static final int DARK_MODE_START_HOUR = 21; // 21:00 (9:00 PM)
    public static final int DARK_MODE_END_HOUR = 7;    // 07:00 (7:00 AM)

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH);
    private static final DateTimeFormatter FULL_DATE_FORMATTER = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH);

    /**
     * Determines whether the given LocalDateTime falls within the dark mode window (21:00 - 07:00).
     *
     * @param time The LocalDateTime to check.
     * @return true if hour is >= 21 or < 7, false otherwise.
     */
    public static boolean isDarkMode(LocalDateTime time) {
        if (time == null) {
            return false;
        }
        return isDarkModeHour(time.getHour());
    }

    /**
     * Checks if a given 24-hour value falls in dark mode (>= 21 or < 7).
     *
     * @param hour Hour of day (0-23).
     * @return true if in dark mode window.
     */
    public static boolean isDarkModeHour(int hour) {
        return hour >= DARK_MODE_START_HOUR || hour < DARK_MODE_END_HOUR;
    }

    /**
     * Formats time as HH:mm:ss.
     *
     * @param time The LocalDateTime to format.
     * @return Formatted time string (e.g., "14:54:20").
     */
    public static String formatTime(LocalDateTime time) {
        if (time == null) {
            return "";
        }
        return time.format(TIME_FORMATTER);
    }

    /**
     * Formats date showing the day of the week, day number, and month name.
     *
     * @param time The LocalDateTime to format.
     * @return Formatted date string (e.g., "Thursday, 20 August").
     */
    public static String formatDate(LocalDateTime time) {
        if (time == null) {
            return "";
        }
        return time.format(DATE_FORMATTER);
    }

    /**
     * Formats full date including the year.
     *
     * @param time The LocalDateTime to format.
     * @return Formatted full date string (e.g., "Thursday, 20 August 2026").
     */
    public static String formatFullDate(LocalDateTime time) {
        if (time == null) {
            return "";
        }
        return time.format(FULL_DATE_FORMATTER);
    }
}
