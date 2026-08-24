package com.example.nuriaassistant.services;

import com.example.nuriaassistant.models.CalendarEvent;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Zero-dependency parser for read-only iCloud calendar feeds (.ics).
 * Supports what a personal calendar actually contains: VEVENT with
 * SUMMARY / DTSTART / DTEND, all-day events (VALUE=DATE), multi-day spans
 * and basic recurrence (FREQ=DAILY|WEEKLY with INTERVAL, BYDAY, UNTIL, COUNT).
 * Exotic RRULEs degrade to their first occurrence instead of failing the feed.
 * Pure static code keeps it unit-testable; parsing runs off the FX thread.
 */
public final class CalendarIcsParser {

    /** Hard caps protecting against pathological feeds. */
    private static final int MAX_EVENTS = 2000;
    private static final int MAX_ITERATIONS_PER_RULE = 5000;

    private CalendarIcsParser() {
    }

    /**
     * Parses an .ics payload and returns occurrences sorted by start time,
     * restricted to the window [from, to] (inclusive dates).
     */
    public static List<CalendarEvent> parse(String ics, LocalDate from, LocalDate to) {
        List<CalendarEvent> out = new ArrayList<>();
        if (ics == null || ics.isBlank()) {
            return out;
        }
        for (Map<String, String> block : veventBlocks(unfold(ics))) {
            if (out.size() >= MAX_EVENTS) {
                break;
            }
            try {
                collectOccurrences(block, from, to, out);
            } catch (Exception ignored) {
                // Skip malformed events instead of rejecting the whole feed
            }
        }
        out.sort(Comparator.comparing(CalendarEvent::start));
        return out;
    }

    // -------------------------------------------------------------------------
    // Line handling (RFC 5545 unfolding + VEVENT blocks)
    // -------------------------------------------------------------------------

    private static List<String> unfold(String ics) {
        List<String> lines = new ArrayList<>();
        for (String raw : ics.split("\r\n|\n|\r")) {
            if ((raw.startsWith(" ") || raw.startsWith("\t")) && !lines.isEmpty()) {
                lines.set(lines.size() - 1, lines.get(lines.size() - 1) + raw.substring(1));
            } else {
                lines.add(raw);
            }
        }
        return lines;
    }

    /** Maps property name -> value; "<NAME>;PARAMS" entries store params under "<NAME>~PARAMS". */
    private static List<Map<String, String>> veventBlocks(List<String> lines) {
        List<Map<String, String>> blocks = new ArrayList<>();
        Map<String, String> current = null;
        for (String line : lines) {
            int colon = splitNameAndValue(line);
            if (colon <= 0) {
                continue;
            }
            String head = line.substring(0, colon);
            String value = unescapeText(line.substring(colon + 1).trim());
            int semi = head.indexOf(';');
            String name = (semi >= 0 ? head.substring(0, semi) : head).trim().toUpperCase(Locale.ROOT);

            switch (name) {
                case "BEGIN" -> {
                    if ("VEVENT".equalsIgnoreCase(value)) {
                        current = new HashMap<>();
                    }
                }
                case "END" -> {
                    if ("VEVENT".equalsIgnoreCase(value) && current != null) {
                        blocks.add(current);
                        current = null;
                    }
                }
                default -> {
                    if (current != null) {
                        String key = semi >= 0 ? name + "~" + head.substring(semi + 1) : name;
                        current.putIfAbsent(key, value);
                    }
                }
            }
        }
        return blocks;
    }

    /** Splits "NAME;PARAM=x:value" at the first colon outside quotes; -1 when absent. */
    private static int splitNameAndValue(String line) {
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (c == ':' && !quoted) {
                return i;
            }
        }
        return -1;
    }

    private static String unescapeText(String text) {
        if (text.indexOf('\\') < 0) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                char next = text.charAt(++i);
                sb.append(switch (next) {
                    case 'n', 'N' -> '\n';
                    default -> next;
                });
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String value(Map<String, String> block, String key) {
        return block.get(key);
    }

    private static boolean hasValueDateParam(String storedKey) {
        return storedKey != null && storedKey.toUpperCase(Locale.ROOT).contains("VALUE=DATE");
    }

    // -------------------------------------------------------------------------
    // Occurrence expansion (single events + basic RRULEs)
    // -------------------------------------------------------------------------

    private static void collectOccurrences(Map<String, String> block, LocalDate from, LocalDate to,
                                           List<CalendarEvent> out) {
        String summaryKey = block.keySet().stream().filter(k -> k.equals("SUMMARY")).findFirst().orElse(null);
        String summary = summaryKey != null ? block.get(summaryKey).trim() : "";

        Map.Entry<String, String> dtStart = findProperty(block, "DTSTART");
        if (dtStart == null) {
            return;
        }
        boolean allDay = looksAllDay(dtStart.getValue()) || hasValueDateParam(dtStart.getKey());
        LocalDateTime start = parseDateTime(dtStart.getValue());
        if (start == null) {
            return;
        }

        LocalDateTime end = null;
        Map.Entry<String, String> dtEnd = findProperty(block, "DTEND");
        if (dtEnd != null) {
            end = parseDateTime(dtEnd.getValue());
        }

        Map.Entry<String, String> rruleEntry = findProperty(block, "RRULE");
        Map<String, String> rule = parseRrule(rruleEntry != null ? rruleEntry.getValue() : null);
        boolean basicRecurrence = rule.containsKey("FREQ")
                && ("DAILY".equals(rule.get("FREQ")) || "WEEKLY".equals(rule.get("FREQ")));

        if (!basicRecurrence) {
            // Single occurrence (or unsupported recurrence: first occurrence only)
            addIfInWindow(out, summary, start, end, allDay, from, to);
            return;
        }
        expandRecurrence(out, summary, start, end, allDay, rule, from, to);
    }

    private static Map.Entry<String, String> findProperty(Map<String, String> block, String name) {
        for (Map.Entry<String, String> e : block.entrySet()) {
            String key = e.getKey();
            if (key.equals(name) || key.startsWith(name + "~")) {
                return Map.entry(key, e.getValue());
            }
        }
        return null;
    }

    /**
     * Expands FREQ=DAILY / FREQ=WEEKLY occurrences (+INTERVAL, BYDAY, UNTIL,
     * COUNT) of an event into the output list. Iteration starts at most two
     * months before the window (ancient history would only waste cycles;
     * COUNT precision degrades gracefully for such feeds).
     */
    private static void expandRecurrence(List<CalendarEvent> out, String summary,
                                         LocalDateTime start, LocalDateTime end, boolean allDay,
                                         Map<String, String> rule, LocalDate from, LocalDate to) {
        boolean daily = "DAILY".equals(rule.get("FREQ"));
        int interval = parsePositiveInt(rule.get("INTERVAL"), 1);
        LocalDateTime until = parseRruleUntil(rule.get("UNTIL"));
        int count = rule.containsKey("COUNT") ? parsePositiveInt(rule.get("COUNT"), Integer.MAX_VALUE)
                : Integer.MAX_VALUE;

        Set<DayOfWeek> byDays;
        if (daily) {
            byDays = null; // every weekday matches
        } else if (rule.containsKey("BYDAY")) {
            byDays = parseByDays(rule.get("BYDAY"));
            if (byDays.isEmpty()) {
                byDays = EnumSet.of(start.getDayOfWeek());
            }
        } else {
            byDays = EnumSet.of(start.getDayOfWeek());
        }

        Duration duration = end != null ? Duration.between(start, end) : null;

        LocalDate floor = from.minusDays(62);
        LocalDate dateCursor = start.toLocalDate().isBefore(floor) ? floor : start.toLocalDate();
        LocalDate limit = to.plusDays(1);
        int produced = 0;
        int iterations = 0;

        while (dateCursor.isBefore(limit) && produced < count && iterations++ < MAX_ITERATIONS_PER_RULE) {
            boolean weekdayMatches = daily
                    || byDays.contains(dateCursor.getDayOfWeek());
            boolean intervalMatches = interval <= 1
                    || java.time.temporal.ChronoUnit.WEEKS.between(start.toLocalDate(), dateCursor) % interval == 0;

            if (weekdayMatches && intervalMatches) {
                LocalDateTime occStart = start.toLocalDate().equals(dateCursor)
                        ? start
                        : LocalDateTime.of(dateCursor, start.toLocalTime());
                if (until != null && occStart.isAfter(until)) {
                    break;
                }
                produced++;
                LocalDateTime occEnd = duration != null ? occStart.plus(duration) : null;
                addIfInWindow(out, summary, occStart, occEnd, allDay, from, to);
                if (occStart.toLocalDate().isAfter(to)) {
                    break; // first occurrence beyond the window: nothing later matters
                }
            }
            dateCursor = dateCursor.plusDays(1);
        }
    }

    private static void addIfInWindow(List<CalendarEvent> out, String title, LocalDateTime start,
                                      LocalDateTime end, boolean allDay, LocalDate from, LocalDate to) {
        LocalDate endDate = (end != null ? end : start).toLocalDate();
        if (!endDate.isBefore(from) && !start.toLocalDate().isAfter(to)) {
            out.add(new CalendarEvent(title, start, end, allDay));
        }
    }

    // -------------------------------------------------------------------------
    // RRULE helpers
    // -------------------------------------------------------------------------

    private static Map<String, String> parseRrule(String rrule) {
        Map<String, String> rule = new HashMap<>();
        if (rrule == null || rrule.isBlank()) {
            return rule;
        }
        for (String part : rrule.trim().split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                rule.put(part.substring(0, eq).trim().toUpperCase(Locale.ROOT),
                        part.substring(eq + 1).trim());
            }
        }
        return rule;
    }

    private static Set<DayOfWeek> parseByDays(String byDay) {
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        for (String token : byDay.split(",")) {
            switch (token.trim().toUpperCase(Locale.ROOT)) {
                case "MO" -> days.add(DayOfWeek.MONDAY);
                case "TU" -> days.add(DayOfWeek.TUESDAY);
                case "WE" -> days.add(DayOfWeek.WEDNESDAY);
                case "TH" -> days.add(DayOfWeek.THURSDAY);
                case "FR" -> days.add(DayOfWeek.FRIDAY);
                case "SA" -> days.add(DayOfWeek.SATURDAY);
                case "SU" -> days.add(DayOfWeek.SUNDAY);
                default -> { /* ordinal prefixes like 2MO unsupported -> skip */ }
            }
        }
        return days;
    }

    private static LocalDateTime parseRruleUntil(String until) {
        if (until == null || until.isBlank()) {
            return null;
        }
        try {
            if (looksAllDay(until)) {
                return LocalDate.parse(until, DateTimeFormatter.BASIC_ISO_DATE).plusDays(1).atStartOfDay();
            }
            return parseIcsDateTime(until);
        } catch (DateTimeParseException e) {
            return null; // unknown UNTIL format -> treat as endless
        }
    }

    // -------------------------------------------------------------------------
    // Date/time decoding (DATE, floating/local/TZID, UTC)
    // -------------------------------------------------------------------------

    private static boolean looksAllDay(String raw) {
        return raw != null && raw.length() == 8 && raw.chars().allMatch(Character::isDigit);
    }

    private static LocalDateTime parseDateTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        if (looksAllDay(value)) {
            try {
                return LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay();
            } catch (DateTimeParseException e) {
                return null;
            }
        }
        try {
            return parseIcsDateTime(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * Decodes YYYYMMDDTHHMMSS(Z?). Floating and TZID times are treated as
     * wall-clock in the Pi's timezone (correct for a single-timezone home
     * assistant); explicit UTC times are converted.
     */
    private static LocalDateTime parseIcsDateTime(String value) throws DateTimeParseException {
        if (value.endsWith("Z")) {
            LocalDateTime utc = LocalDateTime.parse(
                    value.substring(0, value.length() - 1),
                    DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"));
            return utc.atZone(ZoneId.of("UTC")).toLocalDateTime();
        }
        return LocalDateTime.parse(value.substring(0, Math.min(15, value.length())),
                DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"));
    }

    private static int parsePositiveInt(String raw, int fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
