package com.example.nuriaassistant.spotify;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class SpotifyPairingTest {

    @Test
    void tokenIsRandomHex() {
        String first = SpotifyPairing.newToken();
        String second = SpotifyPairing.newToken();

        assertEquals(24, first.length());
        assertTrue(first.matches("[0-9a-f]{24}"));
        assertNotEquals(first, second, "tokens must not repeat");
    }

    @Test
    void stateRoundTripsThroughEncodeAndParse() {
        SpotifyPairing.State state = new SpotifyPairing.State("abc123", "192.168.1.50", 8888);

        String encoded = state.encode();
        assertEquals("abc123~192.168.1.50~8888", encoded);

        SpotifyPairing.State parsed = SpotifyPairing.parse(encoded);
        assertNotNull(parsed);
        assertEquals("abc123", parsed.token());
        assertEquals("192.168.1.50", parsed.host());
        assertEquals(8888, parsed.port());
        assertEquals("http://192.168.1.50:8888/callback", parsed.callbackUrl());
    }

    @Test
    void stateAcceptsMdnsHost() {
        SpotifyPairing.State parsed = SpotifyPairing.parse("tok~alpha.local~8888");
        assertNotNull(parsed);
        assertEquals("alpha.local", parsed.host());
    }

    @Test
    void parseRejectsMalformedOrForeignStates() {
        assertNull(SpotifyPairing.parse(null));
        assertNull(SpotifyPairing.parse(""));
        assertNull(SpotifyPairing.parse("   "));
        assertNull(SpotifyPairing.parse("justatoken"));
        assertNull(SpotifyPairing.parse("~192.168.1.50~8888"), "missing token");
        assertNull(SpotifyPairing.parse("tok~~8888"), "missing host");
        assertNull(SpotifyPairing.parse("tok~192.168.1.50~"), "missing port");
        assertNull(SpotifyPairing.parse("tok~192.168.1.50~abc"), "non-numeric port");
        assertNull(SpotifyPairing.parse("tok~192.168.1.50~0"), "out of range port");
        assertNull(SpotifyPairing.parse("tok~192.168.1.50~70000"), "out of range port");
        assertNull(SpotifyPairing.parse("tok~evil.example.com~8888"), "external host");
        assertNull(SpotifyPairing.parse("tok~192.168.1.999~8888"), "invalid octet");
        assertNull(SpotifyPairing.parse("tok~foo.local~8888~extra"), "extra field");
    }

    @Test
    void hostValidationOnlyAllowsLanAddresses() {
        assertTrue(SpotifyPairing.isValidHost("192.168.1.50"));
        assertTrue(SpotifyPairing.isValidHost("10.0.0.7"));
        assertTrue(SpotifyPairing.isValidHost("alpha.local"));
        assertTrue(SpotifyPairing.isValidHost("ALPHA.local"));

        assertFalse(SpotifyPairing.isValidHost(null));
        assertFalse(SpotifyPairing.isValidHost(""));
        assertFalse(SpotifyPairing.isValidHost("example.com"));
        assertFalse(SpotifyPairing.isValidHost("localhost"));
        assertFalse(SpotifyPairing.isValidHost("256.1.1.1"));
        assertFalse(SpotifyPairing.isValidHost("192.168.1.50/../evil"));
        assertFalse(SpotifyPairing.isValidHost("alpha.local.evil.com"));
    }

    @Test
    void resolveHostAlwaysAnswersSomething() {
        String host = SpotifyPairing.resolveHost();
        assertNotNull(host);
        assertFalse(host.isBlank());
        assertTrue(SpotifyPairing.isValidHost(host));
    }
}
