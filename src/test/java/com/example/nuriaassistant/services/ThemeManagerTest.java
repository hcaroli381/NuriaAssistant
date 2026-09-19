package com.example.nuriaassistant.services;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.Month;

import static org.junit.jupiter.api.Assertions.*;

public class ThemeManagerTest {

    @Test
    void testDarkModeHours() {
        // Dark mode hours (21:00 to 07:00)
        assertTrue(ThemeManager.isDarkModeHour(21), "21:00 should be dark mode");
        assertTrue(ThemeManager.isDarkModeHour(22), "22:00 should be dark mode");
        assertTrue(ThemeManager.isDarkModeHour(23), "23:00 should be dark mode");
        assertTrue(ThemeManager.isDarkModeHour(0), "00:00 should be dark mode");
        assertTrue(ThemeManager.isDarkModeHour(1), "01:00 should be dark mode");
        assertTrue(ThemeManager.isDarkModeHour(5), "05:00 should be dark mode");
        assertTrue(ThemeManager.isDarkModeHour(6), "06:00 should be dark mode");

        // Day mode hours (07:00 to 20:59)
        assertFalse(ThemeManager.isDarkModeHour(7), "07:00 should be day mode");
        assertFalse(ThemeManager.isDarkModeHour(8), "08:00 should be day mode");
        assertFalse(ThemeManager.isDarkModeHour(12), "12:00 should be day mode");
        assertFalse(ThemeManager.isDarkModeHour(15), "15:00 should be day mode");
        assertFalse(ThemeManager.isDarkModeHour(20), "20:00 should be day mode");
    }

    @Test
    void testDarkModeLocalDateTime() {
        LocalDateTime evening = LocalDateTime.of(2026, Month.AUGUST, 20, 21, 0, 0);
        assertTrue(ThemeManager.isDarkMode(evening));

        LocalDateTime lateNight = LocalDateTime.of(2026, Month.AUGUST, 20, 23, 45, 0);
        assertTrue(ThemeManager.isDarkMode(lateNight));

        LocalDateTime earlyMorning = LocalDateTime.of(2026, Month.AUGUST, 20, 6, 59, 59);
        assertTrue(ThemeManager.isDarkMode(earlyMorning));

        LocalDateTime morningStart = LocalDateTime.of(2026, Month.AUGUST, 20, 7, 0, 0);
        assertFalse(ThemeManager.isDarkMode(morningStart));

        LocalDateTime afternoon = LocalDateTime.of(2026, Month.AUGUST, 20, 14, 30, 0);
        assertFalse(ThemeManager.isDarkMode(afternoon));

        LocalDateTime eveningBeforeDark = LocalDateTime.of(2026, Month.AUGUST, 20, 20, 59, 59);
        assertFalse(ThemeManager.isDarkMode(eveningBeforeDark));

        assertFalse(ThemeManager.isDarkMode(null));
    }

    @Test
    void testDateFormattingShowsDayAndMonth() {
        LocalDateTime date = LocalDateTime.of(2026, Month.AUGUST, 20, 14, 54, 30);
        String formattedDate = ThemeManager.formatDate(date);
        assertEquals("Jueves, 20 de agosto", formattedDate);

        String fullDate = ThemeManager.formatFullDate(date);
        assertEquals("Jueves, 20 de agosto de 2026", fullDate);

        LocalDateTime newYears = LocalDateTime.of(2027, Month.JANUARY, 1, 0, 0, 0);
        assertEquals("Viernes, 1 de enero", ThemeManager.formatDate(newYears));
        assertEquals("Viernes, 1 de enero de 2027", ThemeManager.formatFullDate(newYears));
    }

    @Test
    void testGreetingFollowsTheDayAndTheName() {
        assertEquals("Buenos días, Nuria",
                ThemeManager.greeting(LocalDateTime.of(2026, Month.AUGUST, 20, 7, 30), "Nuria"));
        assertEquals("Buenas tardes, Nuria",
                ThemeManager.greeting(LocalDateTime.of(2026, Month.AUGUST, 20, 17, 0), "Nuria"));
        assertEquals("Buenas noches, Nuria",
                ThemeManager.greeting(LocalDateTime.of(2026, Month.AUGUST, 20, 23, 0), "Nuria"));
        assertEquals("Buenas noches, Nuria",
                ThemeManager.greeting(LocalDateTime.of(2026, Month.AUGUST, 20, 4, 0), "Nuria"));

        // No name configured: she still greets, just without addressing anyone.
        assertEquals("Buenos días",
                ThemeManager.greeting(LocalDateTime.of(2026, Month.AUGUST, 20, 8, 0), null));
        assertEquals("Buenos días",
                ThemeManager.greeting(LocalDateTime.of(2026, Month.AUGUST, 20, 8, 0), "  "));
        assertEquals("", ThemeManager.greeting(null, "Nuria"));
    }

    @Test
    void testTimeFormatting() {
        LocalDateTime time = LocalDateTime.of(2026, Month.AUGUST, 20, 14, 54, 30);
        assertEquals("14:54:30", ThemeManager.formatTime(time));
    }
}
