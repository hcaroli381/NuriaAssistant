package com.example.nuriaassistant.services;
import com.example.nuriaassistant.util.Log;

import com.example.nuriaassistant.models.Alarm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manages alarm persistence (zero-dependency JSON at ~/.alpha/alarms.json)
 * and due-time evaluation. Designed for a 1-second polling cadence from the
 * JavaFX clock tick: findDueAlarm() is allocation-light and marks the fired
 * alarm before returning so repeated ticks never double-ring.
 */
public class AlarmService {

    private static final int DEFAULT_SNOOZE_MINUTES = 5;

    private final Path storageFile;
    private final List<Alarm> alarms = new ArrayList<>();

    public AlarmService() {
        this(Path.of(System.getProperty("user.home"), ".alpha", "alarms.json"));
    }

    public AlarmService(Path storageFile) {
        this.storageFile = storageFile;
        load();
    }

    /** Read-only view of all configured alarms. */
    public List<Alarm> getAlarms() {
        return new ArrayList<>(alarms);
    }

    public void addAlarm(Alarm alarm) {
        alarms.add(alarm);
        save();
    }

    public boolean removeAlarm(String id) {
        boolean removed = alarms.removeIf(a -> a.id().equals(id));
        if (removed) {
            save();
        }
        return removed;
    }

    public void updateAlarm(Alarm updated) {
        for (int i = 0; i < alarms.size(); i++) {
            if (alarms.get(i).id().equals(updated.id())) {
                alarms.set(i, updated);
                save();
                return;
            }
        }
    }

    /**
     * Returns the first alarm due at the given moment, marking it fired.
     * Empty Optional when nothing is due.
     */
    public synchronized Optional<Alarm> findDueAlarm(LocalDateTime now) {
        for (Alarm alarm : alarms) {
            if (alarm.isDue(now)) {
                alarm.markFired(now.toLocalDate());
                save();
                return Optional.of(alarm);
            }
        }
        return Optional.empty();
    }

    /** Schedules a re-ring of the given alarm in snoozeMinutes from the wall clock. */
    public synchronized void snooze(Alarm alarm, int snoozeMinutes) {
        snooze(alarm, snoozeMinutes, LocalDateTime.now());
    }

    /** Schedules a re-ring of the given alarm in snoozeMinutes from an explicit base time (testable). */
    public synchronized void snooze(Alarm alarm, int snoozeMinutes, LocalDateTime now) {
        alarm.snoozeUntil(now.plusMinutes(snoozeMinutes));
        save();
    }

    /**
     * Next enabled upcoming occurrence description, or null when no active alarms.
     */
    public synchronized String nextAlarmSummary() {
        LocalDateTime best = null;
        Alarm bestAlarm = null;
        LocalDateTime now = LocalDateTime.now();

        for (Alarm alarm : alarms) {
            if (!alarm.isEnabled()) {
                continue;
            }
            LocalDateTime candidate = nextOccurrence(alarm, now);
            if (candidate != null && (best == null || candidate.isBefore(best))) {
                best = candidate;
                bestAlarm = alarm;
            }
        }

        if (bestAlarm == null) {
            return null;
        }
        if (bestAlarm.snoozeUntil() != null && !now.isBefore(bestAlarm.snoozeUntil())) {
            return "Posponer " + bestAlarm.displayTime();
        }
        long minutesUntil = java.time.Duration.between(now, best).toMinutes();
        if (minutesUntil < 60) {
            return bestAlarm.displayTime() + " en " + Math.max(1, minutesUntil) + " min";
        }
        if (java.time.Duration.between(now, best).toHours() < 24
                && best.toLocalDate().equals(LocalDate.now())) {
            return "Hoy " + bestAlarm.displayTime();
        }
        if (best.toLocalDate().equals(LocalDate.now().plusDays(1))) {
            return "Mañana " + bestAlarm.displayTime();
        }
        return bestAlarm.displayTime();
    }

    private LocalDateTime nextOccurrence(Alarm alarm, LocalDateTime now) {
        if (alarm.snoozeUntil() != null) {
            return alarm.snoozeUntil();
        }
        // Scan up to 8 days ahead for the next matching day
        for (int daysAhead = 0; daysAhead < 8; daysAhead++) {
            LocalDate date = now.toLocalDate().plusDays(daysAhead);
            if (!alarm.firesOn(date.getDayOfWeek())) {
                continue;
            }
            LocalDateTime candidate = LocalDateTime.of(date, java.time.LocalTime.of(alarm.hour(), alarm.minute()));
            if (alarm.lastFiredDate() != null && alarm.lastFiredDate().equals(date)) {
                continue; // already rang today
            }
            if (candidate.isAfter(now)) {
                return candidate;
            }
        }
        return null;
    }

    // =========================================================================
    // Persistence (minimal hand-rolled JSON, same style as TelegramService)
    // =========================================================================

    public synchronized void load() {
        alarms.clear();
        if (!Files.exists(storageFile)) {
            return;
        }
        try {
            String json = Files.readString(storageFile, StandardCharsets.UTF_8);
            parseAlarms(json, alarms);
        } catch (Exception e) {
            Log.error("Alarm", "AlarmService: failed to load alarms: " + e.getMessage());
        }
    }

    public synchronized void save() {
        try {
            Path parent = storageFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = storageFile.resolveSibling(storageFile.getFileName() + ".tmp");
            Files.writeString(tmp, toJson(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, storageFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, storageFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Log.error("Alarm", "AlarmService: failed to save alarms: " + e.getMessage());
        }
    }

    static void parseAlarms(String json, List<Alarm> out) {
        if (json == null || json.isBlank()) {
            return;
        }
        Matcher objectMatcher = Pattern.compile("\\{([\\s\\S]*?)}").matcher(json);
        while (objectMatcher.find()) {
            String body = objectMatcher.group(1);
            try {
                Alarm alarm = new Alarm();
                alarm.setId(extractString(body, "id"));
                alarm.setHour((int) extractLong(body, "hour"));
                alarm.setMinute((int) extractLong(body, "minute"));
                alarm.setEnabled(extractBool(body, "enabled"));
                alarm.setLabel(extractString(body, "label"));
                alarm.setRepeatDays(extractDays(body, "repeatDays"));
                alarm.setOnce(extractBool(body, "once"));
                alarm.setLastFiredDate(extractDate(body, "lastFiredDate"));
                alarm.setSnoozeUntil(extractDateTime(body, "snoozeUntil"));
                out.add(alarm);
            } catch (Exception ignored) {
                // Skip malformed entries rather than failing the whole file
            }
        }
    }

    String toJson() {
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < alarms.size(); i++) {
            Alarm a = alarms.get(i);
            sb.append("  {\"id\": \"").append(escape(a.id())).append("\", ")
              .append("\"hour\": ").append(a.hour()).append(", ")
              .append("\"minute\": ").append(a.minute()).append(", ")
              .append("\"enabled\": ").append(a.isEnabled()).append(", ")
              .append("\"once\": ").append(a.isOnce()).append(", ")
              .append("\"label\": \"").append(escape(a.label())).append("\", ")
              .append("\"repeatDays\": [");
            int d = 0;
            for (DayOfWeek day : a.repeatDays()) {
                if (d++ > 0) {
                    sb.append(", ");
                }
                sb.append('"').append(day.name()).append('"');
            }
            sb.append("], ");
            sb.append("\"lastFiredDate\": ").append(a.lastFiredDate() != null ? "\"" + a.lastFiredDate() + "\"" : "null").append(", ");
            sb.append("\"snoozeUntil\": ").append(a.snoozeUntil() != null ? "\"" + a.snoozeUntil() + "\"" : "null");
            sb.append("}");
            if (i < alarms.size() - 1) {
                sb.append(",");
            }
            sb.append("\n");
        }
        sb.append("]");
        return sb.toString();
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String extractString(String body, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"((?:\\\\\"|[^\"])*)\"").matcher(body);
        return m.find() ? m.group(1).replace("\\\"", "\"") : "";
    }

    private static long extractLong(String body, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(-?\\d+)").matcher(body);
        return m.find() ? Long.parseLong(m.group(1)) : 0L;
    }

    private static boolean extractBool(String body, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(true|false)").matcher(body);
        return m.find() && Boolean.parseBoolean(m.group(1));
    }

    private static Set<DayOfWeek> extractDays(String body, String key) {
        Set<DayOfWeek> days = new LinkedHashSet<>();
        Matcher arrayMatcher = Pattern.compile("\"" + key + "\"\\s*:\\s*\\[([\\s\\S]*?)]").matcher(body);
        if (arrayMatcher.find()) {
            Matcher nameMatcher = Pattern.compile("\"([A-Z]+)\"").matcher(arrayMatcher.group(1));
            while (nameMatcher.find()) {
                try {
                    days.add(DayOfWeek.valueOf(nameMatcher.group(1)));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        return days;
    }

    private static LocalDate extractDate(String body, String key) {
        String value = extractString(body, key);
        return value.isBlank() ? null : LocalDate.parse(value);
    }

    private static LocalDateTime extractDateTime(String body, String key) {
        String value = extractString(body, key);
        return value.isBlank() || "null".equals(value) ? null : LocalDateTime.parse(value);
    }
}
