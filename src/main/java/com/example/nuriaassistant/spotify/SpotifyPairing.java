package com.example.nuriaassistant.spotify;

import com.example.nuriaassistant.util.Log;

import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Phone-to-Pi handoff for the Spotify QR login.
 *
 * <p>Spotify only accepts {@code http} redirect URIs for loopback literals, so
 * the phone cannot be sent back to a bare LAN address. The whitelisted HTTPS
 * bounce page ({@code docs/spotify-callback.html}) is therefore the only fixed
 * URL in the flow; the Pi's LAN address rides inside OAuth's {@code state}
 * parameter so that page knows where to hand the code back. No relay state, no
 * server-side storage: the code travels phone → page → Pi on the local network.
 *
 * <pre>
 *   state = &lt;random token&gt; ~ &lt;host&gt; ~ &lt;port&gt;
 * </pre>
 *
 * <p>The host field holds one or more candidates (comma-separated, best
 * first): the detected LAN IPv4 and the mDNS name. A Pi that changed address
 * after the QR was drawn is still reachable through the second candidate, so
 * the bounce page can offer it as a fallback link.
 */
public final class SpotifyPairing {

    /** State field separator (unreserved in URLs, so Spotify echoes it verbatim). */
    public static final String SEPARATOR = "~";

    /** Port of the Pi's embedded OAuth callback server. */
    public static final int CALLBACK_PORT = 8888;

    /** Callback path handled by {@code SpotifyService#startAuthCallbackServer}. */
    public static final String CALLBACK_PATH = "/callback";

    /**
     * Reachability probe served by the callback server. Opening
     * {@code http://<pi>:8888/ping} on the phone answers "can the phone see the
     * Pi at all?" without involving Spotify — the fastest way to tell a
     * network problem from a Spotify/Dashboard one.
     */
    public static final String PING_PATH = "/ping";

    /** Separator between host candidates inside the state's host field. */
    public static final String HOST_SEPARATOR = ",";

    /** Maximum number of host candidates accepted in a state. */
    private static final int MAX_HOSTS = 4;

    /** Used when the LAN address cannot be detected (Avahi/mDNS name). */
    public static final String MDNS_FALLBACK_HOST = "alpha.local";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 12;

    private SpotifyPairing() {
    }

    /** Token + LAN endpoint the phone must be able to reach. */
    public record State(String token, String host, int port) {

        /** Round-trip-safe state string for the authorize URL. */
        public String encode() {
            return token + SEPARATOR + host + SEPARATOR + port;
        }

        /** Host candidates in try order (never empty for a valid state). */
        public List<String> hosts() {
            return hostsOf(host);
        }

        /** The first (best) host candidate. */
        public String primaryHost() {
            List<String> candidates = hosts();
            return candidates.isEmpty() ? host : candidates.get(0);
        }

        /** The URL the bounce page sends the phone to. */
        public String callbackUrl() {
            return "http://" + primaryHost() + ":" + port + CALLBACK_PATH;
        }
    }

    /** A fresh random pairing state pointing at this Pi. */
    public static State newState() {
        return new State(newToken(), resolveHosts(), CALLBACK_PORT);
    }

    /** Splits a state host field into its trimmed candidates. */
    public static List<String> hostsOf(String hostField) {
        List<String> candidates = new ArrayList<>();
        if (hostField == null) {
            return candidates;
        }
        for (String candidate : hostField.split(Pattern.quote(HOST_SEPARATOR))) {
            String trimmed = candidate.trim();
            if (!trimmed.isEmpty()) {
                candidates.add(trimmed);
            }
        }
        return candidates;
    }

    /** URL-safe random token (no state guessing from the phone's page). */
    public static String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** Parses a state string; null when it is missing or malformed. */
    public static State parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        int first = raw.indexOf(SEPARATOR);
        int last = raw.lastIndexOf(SEPARATOR);
        if (first <= 0 || last <= first + 1 || last == raw.length() - 1) {
            return null;
        }
        String token = raw.substring(0, first);
        String host = raw.substring(first + SEPARATOR.length(), last);
        try {
            int port = Integer.parseInt(raw.substring(last + SEPARATOR.length()));
            if (port < 1 || port > 65535 || !isValidHost(host)) {
                return null;
            }
            return new State(token, host, port);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** True for a valid host field: one or more LAN candidates, never an external host. */
    public static boolean isValidHost(String hostField) {
        List<String> candidates = hostsOf(hostField);
        return !candidates.isEmpty()
                && candidates.size() <= MAX_HOSTS
                && candidates.stream().allMatch(SpotifyPairing::isValidSingleHost);
    }

    /** True for IPv4 literals and mDNS names — never an arbitrary external host. */
    public static boolean isValidSingleHost(String host) {
        if (host == null || host.isBlank() || host.length() > 253) {
            return false;
        }
        if (host.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            for (String part : host.split("\\.")) {
                if (Integer.parseInt(part) > 255) {
                    return false;
                }
            }
            return true;
        }
        return host.toLowerCase().matches("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.local)");
    }

    /**
     * Host candidates for the phone, best first: the detected LAN address when
     * there is one, plus the mDNS name so a Pi whose DHCP lease changed is
     * still reachable from the bounce page's fallback link.
     */
    public static String resolveHosts() {
        String detected = detectLanIpv4();
        if (detected == null) {
            Log.info("Spotify", "SpotifyPairing: no LAN IPv4 detected, falling back to " + MDNS_FALLBACK_HOST);
            return MDNS_FALLBACK_HOST;
        }
        return detected + HOST_SEPARATOR + MDNS_FALLBACK_HOST;
    }

    /**
     * LAN address of this Pi, or the mDNS name when no address can be resolved.
     * The default-route probe opens no connection: connecting a UDP socket only
     * asks the OS which local interface would be used.
     */
    public static String resolveHost() {
        return hostsOf(resolveHosts()).get(0);
    }

    /** Best-effort LAN IPv4: default-route interface first, then any site-local one. */
    public static String detectLanIpv4() {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.connect(InetAddress.getByName("8.8.8.8"), 53);
            InetAddress local = socket.getLocalAddress();
            if (local instanceof Inet4Address && !local.isLoopbackAddress() && local.isSiteLocalAddress()) {
                return local.getHostAddress();
            }
        } catch (Exception ignored) {
            // offline / no route: fall through to the interface scan
        }

        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) {
                    continue;
                }
                for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress() && address.isSiteLocalAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Log.error("Spotify", "SpotifyPairing: interface scan failed: " + e.getMessage());
        }
        return null;
    }
}
