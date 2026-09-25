package com.example.nuriaassistant.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Telegram {@code /calendario} command stores whatever looks like a share
 * link, so this guard is what keeps a pasted sentence or a truncated URL from
 * being persisted and silently disabling the calendar.
 */
public class CalendarServiceTest {

    @Test
    void testAcceptsICloudWebcalShareLink() {
        assertTrue(CalendarService.isPlausibleShareLink(
                "webcal://p01-caldav.icloud.com/published/2/MTIzNDU2Nzg5MA"));
    }

    @Test
    void testAcceptsPlainHttpsLink() {
        assertTrue(CalendarService.isPlausibleShareLink(
                "https://p01-caldav.icloud.com/published/2/MTAyNDM0NTY"));
        assertTrue(CalendarService.isPlausibleShareLink(
                "https://calendar.google.com/calendar/ical/x/basic.ics"));
    }

    @Test
    void testRejectsPlainText() {
        assertFalse(CalendarService.isPlausibleShareLink(null));
        assertFalse(CalendarService.isPlausibleShareLink(""));
        assertFalse(CalendarService.isPlausibleShareLink("hola, aquí va el enlace"));
        assertFalse(CalendarService.isPlausibleShareLink("pescado"));
    }

    @Test
    void testRejectsLinksWithoutSchemeOrHost() {
        assertFalse(CalendarService.isPlausibleShareLink("p01-caldav.icloud.com/published/2/abc"));
        assertFalse(CalendarService.isPlausibleShareLink("ftp://example.com/cal.ics"));
        assertFalse(CalendarService.isPlausibleShareLink("https://localhost/cal.ics"));
    }

    @Test
    void testRejectsUrlsWithSpaces() {
        assertFalse(CalendarService.isPlausibleShareLink(
                "webcal://p01.icloud.com/published/ abc"), "A pasted phrase is not a URL");
    }

    @Test
    void testShortButWellFormedLinkStillRejected() {
        assertFalse(CalendarService.isPlausibleShareLink("https://a.b"));
    }
}
