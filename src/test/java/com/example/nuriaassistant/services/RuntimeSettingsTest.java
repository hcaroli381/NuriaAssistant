package com.example.nuriaassistant.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class RuntimeSettingsTest {

    @TempDir
    Path tempDir;

    private RuntimeSettings newStore() {
        return new RuntimeSettings(tempDir.resolve("settings.json"));
    }

    @Test
    void testSetPersistsAcrossReload() {
        RuntimeSettings store = newStore();
        store.set("CALENDAR_ICS_URL", "https://p01.icloud.com/published/abc.ics");

        RuntimeSettings reloaded = newStore();
        assertEquals("https://p01.icloud.com/published/abc.ics",
                reloaded.get("CALENDAR_ICS_URL"));
    }

    @Test
    void testUnknownKeyIsNull() {
        assertNull(newStore().get("NEVER_SET"));
    }

    @Test
    void testBlankValueRemovesTheKey() {
        RuntimeSettings store = newStore();
        store.set("CALENDAR_ICS_URL", "https://example.com/cal.ics");
        store.set("CALENDAR_ICS_URL", "   ");

        assertNull(store.get("CALENDAR_ICS_URL"));
        assertNull(newStore().get("CALENDAR_ICS_URL"), "Removal must reach the file too");
    }

    @Test
    void testValuesWithQuotesAndBackslashesRoundTrip() {
        RuntimeSettings store = newStore();
        // A URL with a query string contains characters the JSON writer must escape.
        String tricky = "https://example.com/cal.ics?a=1&b=\"x\"&c=\\y";
        store.set("CALENDAR_ICS_URL", tricky);

        assertEquals(tricky, newStore().get("CALENDAR_ICS_URL"));
    }

    @Test
    void testCreatesMissingParentDirectory() {
        Path nested = tempDir.resolve("deep/er/settings.json");
        RuntimeSettings store = new RuntimeSettings(nested);
        store.set("KEY", "value");

        assertTrue(Files.exists(nested), "settings file must be written inside the missing dirs");
        assertEquals("value", new RuntimeSettings(nested).get("KEY"));
    }

    @Test
    void testMalformedFileIsIgnoredGracefully() throws Exception {
        Path file = tempDir.resolve("broken.json");
        Files.writeString(file, "{ not json at all ]]");

        RuntimeSettings store = new RuntimeSettings(file);
        assertNull(store.get("ANYTHING"));
        store.set("KEY", "value");
        assertEquals("value", new RuntimeSettings(file).get("KEY"));
    }

    @Test
    void testPickPrefersTheRuntimeValue() {
        assertEquals("http://runtime", RuntimeSettings.pick("http://runtime", "http://config"));
        assertEquals("http://config", RuntimeSettings.pick(null, "http://config"));
        assertEquals("http://config", RuntimeSettings.pick("  ", "http://config"));
        assertEquals(null, RuntimeSettings.pick(null, null));
    }
}
