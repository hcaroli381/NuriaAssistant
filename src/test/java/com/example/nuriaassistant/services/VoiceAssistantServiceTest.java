package com.example.nuriaassistant.services;

import com.example.nuriaassistant.models.VoiceAssistantSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class VoiceAssistantServiceTest {

    @Test
    void testParseFullSnapshot() {
        String json = """
                {
                  "state": "speaking",
                  "wake_word_score": 0.87,
                  "wake_word_model": "hey_jarvis",
                  "last_transcript": "que hora es",
                  "last_reply": "Son las 14:54.",
                  "last_action": "get_time",
                  "last_error": "",
                  "last_event_at": 1755838470.12,
                  "running": true,
                  "last_event_at_iso": "2026-08-22T00:14:30"
                }
                """;

        VoiceAssistantSnapshot snapshot = VoiceAssistantService.parseSnapshot(json);

        assertEquals("speaking", snapshot.state());
        assertEquals(0.87, snapshot.wakeWordScore(), 0.0001);
        assertEquals("que hora es", snapshot.lastTranscript());
        assertEquals("Son las 14:54.", snapshot.lastReply());
        assertEquals("", snapshot.lastError());
        assertTrue(snapshot.running());
        assertFalse(snapshot.offline());
        assertTrue(snapshot.isSpeaking());
        assertTrue(snapshot.isActive());
    }

    @Test
    void testParseEscapedStrings() {
        String json = """
                {"state":"idle", "last_transcript":"dime \\"la hora\\" por favor", "last_reply":"linea uno\\nlinea dos"}
                """;

        VoiceAssistantSnapshot snapshot = VoiceAssistantService.parseSnapshot(json);

        assertEquals("idle", snapshot.state());
        assertEquals("dime \"la hora\" por favor", snapshot.lastTranscript());
        assertEquals("linea uno\nlinea dos", snapshot.lastReply());
    }

    @Test
    void testMissingFieldsFallBackToDefaults() {
        String json = "{\"state\":\"listening\"}";

        VoiceAssistantSnapshot snapshot = VoiceAssistantService.parseSnapshot(json);

        assertEquals("listening", snapshot.state());
        assertEquals(0.0, snapshot.wakeWordScore());
        assertEquals("", snapshot.lastTranscript());
        assertEquals("", snapshot.lastReply());
        assertFalse(snapshot.running());
    }

    @Test
    void testNullOrBlankJsonReturnsOffline() {
        assertTrue(VoiceAssistantService.parseSnapshot(null).offline());
        assertTrue(VoiceAssistantService.parseSnapshot("").offline());
        assertTrue(VoiceAssistantService.parseSnapshot("   ").offline());
    }

    @Test
    void testGarbageBodyFallsBackToStopped() {
        VoiceAssistantSnapshot snapshot = VoiceAssistantService.parseSnapshot("not json at all");

        assertEquals("stopped", snapshot.state());
        assertFalse(snapshot.offline());
        assertFalse(snapshot.running());
        assertFalse(snapshot.isActive());
    }

    @Test
    void testErrorStateDetection() {
        String json = "{\"state\":\"error\", \"last_error\":\"Audio overflow on input stream.\", \"running\":true}";

        VoiceAssistantSnapshot snapshot = VoiceAssistantService.parseSnapshot(json);

        assertTrue(snapshot.isError());
        assertEquals("Audio overflow on input stream.", snapshot.lastError());
        assertTrue(snapshot.running());
    }
}
