package com.example.nuriaassistant.models;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * A single alarm configuration. Repeatable by day of week;
 * an empty repeat set means the alarm fires every day.
 */
public class Alarm {

    private String id;
    private int hour;
    private int minute;
    private boolean enabled = true;
    private String label = "";
    private Set<DayOfWeek> repeatDays = new LinkedHashSet<>();
    /** One-shot alarm: disappears after its next ring instead of repeating. */
    private boolean once = false;

    /** Day the alarm last rang (guards against double-firing within the same day). */
    private LocalDate lastFiredDate;

    /** When set and in the future, the alarm rings again at this moment (snooze). */
    private LocalDateTime snoozeUntil;

    public Alarm() {
        this.id = java.util.UUID.randomUUID().toString();
    }

    public Alarm(int hour, int minute, String label, Set<DayOfWeek> repeatDays) {
        this(hour, minute, label, repeatDays, false);
    }

    public Alarm(int hour, int minute, String label, Set<DayOfWeek> repeatDays, boolean once) {
        this();
        this.hour = hour;
        this.minute = minute;
        this.label = label != null ? label : "";
        if (repeatDays != null) {
            this.repeatDays = new LinkedHashSet<>(repeatDays);
        }
        this.once = once;
    }

    public String id() {
        return id;
    }

    public void setId(String id) {
        if (id != null && !id.isBlank()) {
            this.id = id;
        }
    }

    public int hour() {
        return hour;
    }

    public void setHour(int hour) {
        this.hour = Math.floorMod(hour, 24);
    }

    public int minute() {
        return minute;
    }

    public void setMinute(int minute) {
        this.minute = Math.floorMod(minute, 60);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** True when this alarm rings only once and is removed after being dismissed. */
    public boolean isOnce() {
        return once;
    }

    public void setOnce(boolean once) {
        this.once = once;
    }

    public String label() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label != null ? label : "";
    }

    public Set<DayOfWeek> repeatDays() {
        return repeatDays;
    }

    public void setRepeatDays(Set<DayOfWeek> repeatDays) {
        this.repeatDays = repeatDays != null ? new LinkedHashSet<>(repeatDays) : new LinkedHashSet<>();
    }

    public LocalDate lastFiredDate() {
        return lastFiredDate;
    }

    public void setLastFiredDate(LocalDate lastFiredDate) {
        this.lastFiredDate = lastFiredDate;
    }

    public LocalDateTime snoozeUntil() {
        return snoozeUntil;
    }

    public void setSnoozeUntil(LocalDateTime snoozeUntil) {
        this.snoozeUntil = snoozeUntil;
    }

    /**
     * True when the weekly schedule covers the given day.
     * Empty repeat set means every day.
     */
    public boolean firesOn(DayOfWeek day) {
        return repeatDays.isEmpty() || repeatDays.contains(day);
    }

    /**
     * True when the alarm should ring at the given moment:
     * either its scheduled time arrived today, or a snooze expired.
     */
    public boolean isDue(LocalDateTime now) {
        if (!enabled) {
            return false;
        }

        // Snooze expiry has priority (fires even outside schedule)
        if (snoozeUntil != null && !now.isBefore(snoozeUntil)) {
            return true;
        }

        // Scheduled fire: right time, allowed day, not already fired today
        if (lastFiredDate != null && lastFiredDate.equals(now.toLocalDate())) {
            return false;
        }
        // Fire window [target, target + 30s) so a lagging tick never skips the ring
        LocalTime start = LocalTime.of(hour, minute);
        LocalTime nowTime = now.toLocalTime();
        return !nowTime.isBefore(start) && nowTime.isBefore(start.plusSeconds(30))
                && firesOn(now.getDayOfWeek());
    }

    /**
     * Marks this alarm as ringing now: records today as fired and clears any pending snooze.
     * Call immediately after isDue() returns true.
     */
    public void markFired(LocalDate today) {
        this.lastFiredDate = today;
        this.snoozeUntil = null;
    }

    /**
     * Schedules a snooze re-ring.
     */
    public void snoozeUntil(LocalDateTime when) {
        this.snoozeUntil = when;
    }

    /** Human-readable HH:mm. */
    public String displayTime() {
        return String.format(Locale.ROOT, "%02d:%02d", hour, minute);
    }

    /** Short Spanish text for the repeat pattern, e.g. "L-V", "Todos los días", "Solo una vez". */
    public String repeatDescription() {
        if (once) {
            return "Solo una vez";
        }
        if (repeatDays.isEmpty()) {
            return "Todos los días";
        }
        Set<DayOfWeek> weekdays = Set.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY, DayOfWeek.FRIDAY);
        if (repeatDays.equals(weekdays)) {
            return "Lunes a viernes";
        }
        StringBuilder sb = new StringBuilder();
        for (DayOfWeek d : repeatDays) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(switch (d) {
                case MONDAY -> "L";
                case TUESDAY -> "M";
                case WEDNESDAY -> "X";
                case THURSDAY -> "J";
                case FRIDAY -> "V";
                case SATURDAY -> "S";
                case SUNDAY -> "D";
            });
        }
        return sb.toString();
    }
}
