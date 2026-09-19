package com.example.nuriaassistant.spotify;

import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the embedded callback server that the phone (or a desktop browser)
 * hits once Spotify redirects back.
 */
public class SpotifyCallbackServerTest {

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static HttpResponse<String> get(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void acceptsCodeWithStateAndConfirmsOnThePhone() throws Exception {
        SpotifyService service = new SpotifyService("id", "secret", "http://127.0.0.1:8888/callback");
        AtomicReference<SpotifyService.AuthCallback> seen = new AtomicReference<>();
        int port = freePort();

        service.startAuthCallbackServer(callback -> {
            seen.set(callback);
            return true;
        }, port);
        try {
            HttpResponse<String> response =
                    get("http://127.0.0.1:" + port + "/callback?code=CODE42&state=tok~192.168.1.50~8888");

            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("ALPHA"), "page is Alpha-branded");
            assertNotNull(seen.get(), "handler must receive the callback");
            assertEquals("CODE42", seen.get().code());
            assertEquals("tok~192.168.1.50~8888", seen.get().state());
            assertTrue(seen.get().hasCode());
        } finally {
            service.stopAuthCallbackServer();
        }
    }

    @Test
    void rejectsCallbackWithoutCode() throws Exception {
        SpotifyService service = new SpotifyService("id", "secret", "http://127.0.0.1:8888/callback");
        AtomicReference<SpotifyService.AuthCallback> seen = new AtomicReference<>();
        int port = freePort();

        service.startAuthCallbackServer(callback -> {
            seen.set(callback);
            return true;
        }, port);
        try {
            HttpResponse<String> response = get("http://127.0.0.1:" + port + "/callback?error=access_denied");

            assertEquals(400, response.statusCode());
            assertNull(seen.get(), "no handler call without a code");
        } finally {
            service.stopAuthCallbackServer();
        }
    }

    @Test
    void rejectsWhenTheAppRefusesTheState() throws Exception {
        SpotifyService service = new SpotifyService("id", "secret", "http://127.0.0.1:8888/callback");
        int port = freePort();

        service.startAuthCallbackServer(callback -> false, port);
        try {
            HttpResponse<String> response =
                    get("http://127.0.0.1:" + port + "/callback?code=CODE&state=" + SpotifyPairing.newToken());

            assertEquals(400, response.statusCode());
            assertFalse(response.body().contains("conectado. Ya puedes cerrar"));
        } finally {
            service.stopAuthCallbackServer();
        }
    }
}
