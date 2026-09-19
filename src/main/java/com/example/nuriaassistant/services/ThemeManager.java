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

    // Alpha speaks Spanish everywhere else, so her date does too.
    private static final Locale SPANISH = Locale.of("es", "ES");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM", SPANISH);
    private static final DateTimeFormatter FULL_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM 'de' yyyy", SPANISH);

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
     * @return Formatted date string (e.g., "Jueves, 20 de agosto").
     */
    public static String formatDate(LocalDateTime time) {
        if (time == null) {
            return "";
        }
        return capitalizeFirst(time.format(DATE_FORMATTER));
    }

    /**
     * Formats full date including the year.
     *
     * @param time The LocalDateTime to format.
     * @return Formatted full date string (e.g., "Jueves, 20 de agosto de 2026").
     */
    public static String formatFullDate(LocalDateTime time) {
        if (time == null) {
            return "";
        }
        return capitalizeFirst(time.format(FULL_DATE_FORMATTER));
    }

    /**
     * Time-of-day greeting Alpha uses when an alarm wakes the house; the name is
     * optional so an unconfigured install still reads naturally.
     *
     * @param time The LocalDateTime to greet by.
     * @param name Person to address (may be null/blank).
     * @return e.g. "Buenos días, Nuria" or "Buenas noches".
     */
    public static String greeting(LocalDateTime time, String name) {
        if (time == null) {
            return "";
        }
        int hour = time.getHour();
        String greeting;
        if (hour < 6 || hour >= 21) {
            greeting = "Buenas noches";
        } else if (hour < 13) {
            greeting = "Buenos días";
        } else if (hour < 21) {
            greeting = "Buenas tardes";
        } else {
            greeting = "Buenas noches";
        }
        return name == null || name.isBlank() ? greeting : greeting + ", " + name;
    }

    private static String capitalizeFirst(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
