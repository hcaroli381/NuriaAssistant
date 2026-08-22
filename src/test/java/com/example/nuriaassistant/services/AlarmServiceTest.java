package com.example.nuriaassistant.services;

import com.example.nuriaassistant.models.Alarm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class AlarmServiceTest {

    @TempDir
    Path tempDir;

    private AlarmService newService() {
        return new AlarmService(tempDir.resolve("alarms.json"));
    }

    @Test
    void testAddAlarmPersistsAcrossReload() {
        AlarmService service = newService();
        service.addAlarm(new Alarm(7, 30, "Despertar", Set.of()));

        AlarmService reloaded = newService();
        List<Alarm> alarms = reloaded.getAlarms();

        assertEquals(1, alarms.size());
        assertEquals(7, alarms.get(0).hour());
        assertEquals(30, alarms.get(0).minute());
        assertEquals("Despertar", alarms.get(0).label());
        assertTrue(alarms.get(0).isEnabled());
        assertTrue(alarms.get(0).repeatDays().isEmpty(), "Empty repeat set means daily");
    }

    @Test
    void testFindDueAlarmFiresOncePerDay() {
        AlarmService service = newService();
        service.addAlarm(new Alarm(7, 0, "Morning", Set.of()));

        LocalDateTime fireTime = LocalDateTime.of(2026, Month.AUGUST, 22, 7, 0, 10);

        var first = service.findDueAlarm(fireTime);
        assertTrue(first.isPresent(), "Alarm should fire inside its window");
        assertEquals("07:00", first.get().displayTime());

        var secondSameTick = service.findDueAlarm(fireTime.plusSeconds(5));
        assertTrue(secondSameTick.isEmpty(), "Already fired today, must not ring again");

        var nextDay = service.findDueAlarm(fireTime.plusDays(1));
        assertTrue(nextDay.isPresent(), "Daily alarm fires again the next day");
    }

    @Test
    void testDisabledAlarmNeverFires() {
        AlarmService service = newService();
        Alarm alarm = new Alarm(8, 0, "", Set.of());
        alarm.setEnabled(false);
        service.addAlarm(alarm);

        assertTrue(service.findDueAlarm(LocalDateTime.of(2026, 8, 22, 8, 0, 5)).isEmpty(),
                "Disabled alarms must stay silent");
    }

    @Test
    void testRepeatDaysRespected() {
        AlarmService service = newService();
        // Saturday August 22nd, 2026. Monday the 24th.
        service.addAlarm(new Alarm(9, 0, "Weekdays only", Set.of(
                DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)));

        assertFalse(service.findDueAlarm(LocalDateTime.of(2026, 8, 22, 9, 0, 10)).isPresent(),
                "Should not fire on Saturday");
        assertTrue(service.findDueAlarm(LocalDateTime.of(2026, 8, 24, 9, 0, 10)).isPresent(),
                "Should fire on Monday");
    }

    @Test
    void testSnoozeRingsAgainThenClears() {
        AlarmService service = newService();
        Alarm alarm = new Alarm(6, 30, "", Set.of());
        service.addAlarm(alarm);

        LocalDateTime fireTime = LocalDateTime.of(2026, 8, 22, 6, 30, 0);
        assertTrue(service.findDueAlarm(fireTime).isPresent());

        service.snooze(alarm, 5, fireTime);

        assertTrue(service.findDueAlarm(fireTime.plusMinutes(3)).isEmpty(),
                "Snooze not elapsed yet");

        var snoozedRing = service.findDueAlarm(fireTime.plusMinutes(6));
        assertTrue(snoozedRing.isPresent(), "Snoozed alarm rings after the delay");
        assertNull(snoozedRing.get().snoozeUntil(), "Snooze is consumed when it fires");

        assertTrue(service.findDueAlarm(fireTime.plusMinutes(7)).isEmpty(),
                "No further rings after snoozed fire (already marked today)");
    }

    @Test
    void testRemoveAlarm() {
        AlarmService service = newService();
        Alarm alarm = new Alarm(7, 0, "", Set.of());
        service.addAlarm(alarm);

        assertTrue(service.removeAlarm(alarm.id()));
        assertTrue(service.getAlarms().isEmpty());
        assertFalse(service.removeAlarm("nonexistent-id"));
    }

    @Test
    void testJsonRoundTripKeepsAllFields() {
        AlarmService service = newService();
        Alarm complex = new Alarm(21, 45, "Estudiar", Set.of(DayOfWeek.MONDAY, DayOfWeek.FRIDAY));
        complex.setEnabled(false);
        complex.setLastFiredDate(java.time.LocalDate.of(2026, 8, 20));
        complex.setSnoozeUntil(LocalDateTime.of(2026, 8, 21, 6, 35));
        service.addAlarm(complex);

        AlarmService reloaded = newService();
        assertEquals(1, reloaded.getAlarms().size());
        Alarm loaded = reloaded.getAlarms().get(0);

        assertEquals(21, loaded.hour());
        assertEquals(45, loaded.minute());
        assertEquals("Estudiar", loaded.label());
        assertFalse(loaded.isEnabled());
        assertEquals(Set.of(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), loaded.repeatDays());
        assertEquals(java.time.LocalDate.of(2026, 8, 20), loaded.lastFiredDate());
        assertEquals(LocalDateTime.of(2026, 8, 21, 6, 35), loaded.snoozeUntil());
    }

    @Test
    void testOnceAlarmRoundTrip() {
        AlarmService service = newService();
        Alarm onceAlarm = new Alarm(12, 30, "Pastilla", Set.of(), true);
        service.addAlarm(onceAlarm);

        AlarmService reloaded = newService();
        Alarm loaded = reloaded.getAlarms().get(0);
        assertTrue(loaded.isOnce(), "'once' flag must survive persistence");
        assertEquals("Solo una vez", loaded.repeatDescription());
    }

    @Test
    void testMalformedJsonIsIgnoredGracefully() {
        Path file = tempDir.resolve("broken.json");
        try {
            java.nio.file.Files.writeString(file, "{ this is not json ]] ");
        } catch (Exception e) {
            fail("Could not write test file");
        }
        AlarmService service = new AlarmService(file);
        assertTrue(service.getAlarms().isEmpty(), "Broken file should result in zero alarms");
    }

    @Test
    void testNextAlarmSummaryVariants() {
        AlarmService service = newService();
        assertNull(service.nextAlarmSummary(), "No alarms configured yet");

        LocalDateTime now = LocalDateTime.now();

        // Alarm a few minutes from now -> should report minutes remaining
        LocalDateTime soonTime = now.plusMinutes(3);
        Alarm soon = new Alarm(soonTime.getHour(), soonTime.getMinute(), "", Set.of());
        service.addAlarm(soon);
        String summary = service.nextAlarmSummary();
        assertNotNull(summary);
        assertTrue(summary.contains("min"), "Near-term alarm should show minutes remaining: " + summary);

        // Alarm ~30h ahead -> should still produce a non-empty summary
        service.removeAlarm(soon.id());
        LocalDateTime laterTime = now.plusHours(30);
        Alarm later = new Alarm(laterTime.getHour(), laterTime.getMinute(), "", Set.of());
        service.addAlarm(later);
        String laterSummary = service.nextAlarmSummary();
        assertNotNull(laterSummary);
        assertFalse(laterSummary.isBlank());
    }
}
