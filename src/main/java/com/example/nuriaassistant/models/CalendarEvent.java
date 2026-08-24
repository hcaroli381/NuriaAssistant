package com.example.nuriaassistant.models;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A single calendar occurrence (expanded from recurring events by the parser).
 *
 * @param title  Event summary text.
 * @param start  Occurrence start (local wall-clock; UTC inputs converted).
 * @param end    Occurrence end, or null when the feed provides no DTEND.
 * @param allDay True for VALUE=DATE events.
 */
public record CalendarEvent(String title, LocalDateTime start, LocalDateTime end, boolean allDay) {

    /** The date this occurrence happens on (start date; multi-day events count on every day they span). */
    public LocalDate startDate() {
        return start.toLocalDate();
    }

    public boolean overlapsDate(LocalDate date) {
        LocalDate s = start.toLocalDate();
        if (!date.isBefore(s)) {
            if (end == null) {
                return date.equals(s);
            }
            // An event ending at 00:00 of the next day should not claim that day
            LocalDateTime effectiveEnd = end.toLocalTime().equals(java.time.LocalTime.MIDNIGHT)
                    ? end.minusMinutes(1)
                    : end;
            return !date.isAfter(effectiveEnd.toLocalDate());
        }
        return false;
    }

    public String displayTime() {
        if (allDay) {
            return "Todo el día";
        }
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm");
        if (end == null) {
            return start.format(fmt);
        }
        return start.format(fmt) + " – " + end.format(fmt);
    }
}
