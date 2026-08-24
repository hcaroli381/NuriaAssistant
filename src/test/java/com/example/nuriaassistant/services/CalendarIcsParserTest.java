package com.example.nuriaassistant.services;

import com.example.nuriaassistant.models.CalendarEvent;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class CalendarIcsParserTest {

    private static final LocalDate FROM = LocalDate.of(2026, 8, 1);
    private static final LocalDate TO = LocalDate.of(2026, 9, 15);

    @Test
    void parsesSingleTimedEvent() {
        String ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                BEGIN:VEVENT
                UID:1
                DTSTART;TZID=Europe/Madrid:20260824T100000
                DTEND;TZID=Europe/Madrid:20260824T110000
                SUMMARY:Cita con el dentista
                END:VEVENT
                END:VCALENDAR
                """;

        List<CalendarEvent> events = CalendarIcsParser.parse(ics, FROM, TO);

        assertEquals(1, events.size());
        CalendarEvent event = events.get(0);
        assertEquals("Cita con el dentista", event.title());
        assertFalse(event.allDay());
        assertEquals(LocalDate.of(2026, 8, 24).atTime(10, 0), event.start());
        assertNotNull(event.end());
    }

    @Test
    void parsesAllDayEventWithValueDate() {
        String ics = """
                BEGIN:VEVENT
                DTSTART;VALUE=DATE:20260830
                DTEND;VALUE=DATE:20260831
                SUMMARY:Excursión a Sierra Nevada
                END:VEVENT
                """;

        List<CalendarEvent> events = CalendarIcsParser.parse(ics, FROM, TO);

        assertEquals(1, events.size());
        assertTrue(events.get(0).allDay());
        assertEquals("Todo el día", events.get(0).displayTime());
    }

    @Test
    void unfoldsFoldedSummaryLines() {
        // RFC 5545 folding: continuation lines start with a space
        String ics = "BEGIN:VEVENT\r\n"
                + "DTSTART:20260825T180000\r\n"
                + "SUMMARY:Reunión muy larga con\r\n"
                + "  un nombre interminable\r\n"
                + "END:VEVENT\r\n";

        List<CalendarEvent> events = CalendarIcsParser.parse(ics, FROM, TO);

        assertEquals(1, events.size());
        assertEquals("Reunión muy larga con un nombre interminable", events.get(0).title());
    }

    @Test
    void expandsWeeklyByDayRecurrence() {
        String ics = """
                BEGIN:VEVENT
                DTSTART:20260803T090000
                DTEND:20260803T093000
                RRULE:FREQ=WEEKLY;BYDAY=MO,WE
                SUMMARY:Pilates
                END:VEVENT
                """;

        List<CalendarEvent> events = CalendarIcsParser.parse(ics, FROM, TO);

        // Every Monday and Wednesday in the window (Aug 3 is a Monday)
        assertFalse(events.isEmpty());
        for (CalendarEvent event : events) {
            assertTrue(event.start().getDayOfWeek() == DayOfWeek.MONDAY
                    || event.start().getDayOfWeek() == DayOfWeek.WEDNESDAY);
            assertTrue(event.overlapsDate(FROM.minusDays(7)) || !event.start().toLocalDate().isBefore(FROM));
        }
        long mondaysInAugust = events.stream()
                .filter(e -> e.startDate().getMonthValue() == 8)
                .count();
        assertEquals(9, mondaysInAugust); // 5 Mondays + 4 Wednesdays in Aug 2026
    }

    @Test
    void respectsWeeklyIntervalTwo() {
        String ics = """
                BEGIN:VEVENT
                DTSTART:20260804T200000
                RRULE:FREQ=WEEKLY;INTERVAL=2
                SUMMARY:Clase de baile
                END:VEVENT
                """;

        List<CalendarEvent> events = CalendarIcsParser.parse(ics, FROM, TO);

        assertEquals(List.of(LocalDate.of(2026, 8, 4), LocalDate.of(2026, 8, 18),
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 15)),
                events.stream().map(CalendarEvent::startDate).toList());
    }

    @Test
    void stopsDailyAtUntil() {
        String ics = """
                BEGIN:VEVENT
                DTSTART:20260828T080000
                RRULE:FREQ=DAILY;UNTIL=20260830T235959Z
                SUMMARY:Dieta de prueba
                END:VEVENT
                """;

        List<CalendarEvent> events = CalendarIcsParser.parse(ics, FROM, TO);

        assertEquals(3, events.size()); // Aug 28, 29, 30 — nothing on the 31st
    }

    @Test
    void exoticRruleFallsBackToFirstOccurrenceOnly() {
        String ics = """
                BEGIN:VEVENT
                DTSTART:20260820T120000
                RRULE:FREQ=MONTHLY;BYMONTHDAY=20
                SUMMARY:Cumpleaños de papá
                END:VEVENT
                """;

        List<CalendarEvent> events = CalendarIcsParser.parse(ics, FROM, TO);

        assertEquals(1, events.size());
        assertEquals(LocalDate.of(2026, 8, 20), events.get(0).startDate());
    }

    @Test
    void multiDayEventOverlapsEverySpannedDate() {
        String ics = """
                BEGIN:VEVENT
                DTSTART:20260901T100000
                DTEND:20260903T220000
                SUMMARY:Viaje a Barcelona
                END:VEVENT
                """;

        List<CalendarEvent> events = CalendarIcsParser.parse(ics, FROM, TO);

        assertEquals(1, events.size());
        assertTrue(events.get(0).overlapsDate(LocalDate.of(2026, 9, 1)));
        assertTrue(events.get(0).overlapsDate(LocalDate.of(2026, 9, 2)));
        assertTrue(events.get(0).overlapsDate(LocalDate.of(2026, 9, 3)));
        assertFalse(events.get(0).overlapsDate(LocalDate.of(2026, 9, 4)));
    }

    @Test
    void eventsOutsideWindowAreDroppedAndOutputIsSorted() {
        String ics = """
                BEGIN:VEVENT
                DTSTART:20260905T140000
                SUMMARY:Tarde
                END:VEVENT
                BEGIN:VEVENT
                DTSTART:20260905T090000
                SUMMARY:Mañana
                END:VEVENT
                BEGIN:VEVENT
                DTSTART:20251225T000000
                SUMMARY:Lejano
                END:VEVENT
                """;

        List<CalendarEvent> events = CalendarIcsParser.parse(ics, FROM, TO);

        assertEquals(2, events.size());
        assertEquals("Mañana", events.get(0).title());
        assertEquals("Tarde", events.get(1).title());
    }

    @Test
    void blankOrNullFeedYieldsEmptyList() {
        assertTrue(CalendarIcsParser.parse(null, FROM, TO).isEmpty());
        assertTrue(CalendarIcsParser.parse("", FROM, TO).isEmpty());
        assertTrue(CalendarIcsParser.parse("BEGIN:VCALENDAR\r\nEND:VCALENDAR", FROM, TO).isEmpty());
    }
}
