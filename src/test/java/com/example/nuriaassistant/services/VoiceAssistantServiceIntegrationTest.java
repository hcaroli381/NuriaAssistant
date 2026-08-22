package com.example.nuriaassistant.services;

import com.example.nuriaassistant.models.VoiceAssistantSnapshot;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration check against a live backend on 127.0.0.1:8090.
 * Skips silently when the backend is not running (e.g., CI).
 */
public class VoiceAssistantServiceIntegrationTest {

    @Test
    void testLiveBackendSnapshotParsesOnline() throws Exception {
        HttpClient probe = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        HttpRequest health = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:8090/health"))
                .timeout(Duration.ofSeconds(2))
                .GET().build();

        boolean reachable;
        try {
            reachable = probe.send(health, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (Exception e) {
            reachable = false;
        }
        if (!reachable) {
            System.out.println("Backend not running; skipping integration test");
            return;
        }

        HttpRequest stateReq = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:8090/assistant/state"))
                .timeout(Duration.ofSeconds(3))
                .GET().build();
        String body = probe.send(stateReq, HttpResponse.BodyHandlers.ofString()).body();

        VoiceAssistantSnapshot snap = VoiceAssistantService.parseSnapshot(body);
        assertFalse(snap.offline(), "Backend answered but snapshot was marked offline");
        assertNotNull(snap.state());
        System.out.println("LIVE SNAPSHOT ok: state=" + snap.state() + " running=" + snap.running());
    }
}
