package com.example.nuriaassistant.services;
import com.example.nuriaassistant.util.Log;

import java.io.IOException;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * NetworkManager-backed WiFi control for the kiosk: the Pi boots straight into
 * a touchscreen with no keyboard, so a new network has to be chosen and its
 * password typed on screen. Everything goes through {@code nmcli} with
 * argument lists (never a shell), so SSIDs and passwords containing spaces,
 * quotes or {@code $} are safe.
 *
 * <p>It also reports the device MAC address, which is the piece of information
 * that makes a locked-down router usable: most home routers only hand out an
 * address to devices on a whitelist, and the MAC is what gets typed there.
 *
 * <p>On a development machine without NetworkManager every call degrades to an
 * empty result / a friendly failure instead of throwing.
 */
public class WifiService {

    /** A single visible network as reported by {@code nmcli device wifi list}. */
    public record WifiNetwork(String ssid, int signal, String security, boolean active) {
        /** Open networks need no password step. */
        public boolean isOpen() {
            return security == null || security.isBlank() || "--".equals(security.trim());
        }
    }

    /** Result of a connection attempt: a user-facing Spanish message, never an exception. */
    public record ConnectResult(boolean success, String message) {}

    private static final int TIMEOUT_SECONDS = 40;

    private volatile Boolean available;

    // =========================================================================
    // Hardware address
    // =========================================================================

    /**
     * Primary hardware address (WiFi first, then any other non-loopback NIC),
     * formatted {@code AA:BB:CC:DD:EE:FF}. Returns null when nothing is
     * available (e.g. the wireless interface has not been brought up yet).
     */
    public String macAddress() {
        List<NetworkInterface> candidates = networkInterfaces();
        // The wireless NIC is the one a router whitelist cares about.
        NetworkInterface best = null;
        int bestRank = Integer.MAX_VALUE;
        for (NetworkInterface nic : candidates) {
            byte[] mac = hardwareAddress(nic);
            if (mac == null) {
                continue;
            }
            int rank = nic.getName().startsWith("wl") ? 0 : 1;
            if (rank < bestRank) {
                bestRank = rank;
                best = nic;
            }
        }
        return best == null ? null : formatMac(hardwareAddress(best));
    }

    /** All non-loopback hardware addresses, for diagnostics. */
    public List<String> allMacAddresses() {
        List<String> result = new ArrayList<>();
        for (NetworkInterface nic : networkInterfaces()) {
            byte[] mac = hardwareAddress(nic);
            if (mac != null) {
                result.add(nic.getName() + " " + formatMac(mac));
            }
        }
        return result;
    }

    /** Formats six raw bytes as an uppercase colon-separated MAC; testable in isolation. */
    static String formatMac(byte[] mac) {
        if (mac == null || mac.length != 6) {
            return null;
        }
        StringBuilder sb = new StringBuilder(17);
        for (byte b : mac) {
            if (!sb.isEmpty()) {
                sb.append(':');
            }
            sb.append(String.format(Locale.ROOT, "%02X", b));
        }
        return sb.toString();
    }

    private static byte[] hardwareAddress(NetworkInterface nic) {
        try {
            byte[] mac = nic.getHardwareAddress();
            return mac != null && mac.length == 6 ? mac : null;
        } catch (SocketException e) {
            return null;
        }
    }

    private static List<NetworkInterface> networkInterfaces() {
        List<NetworkInterface> result = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
            while (all != null && all.hasMoreElements()) {
                NetworkInterface nic = all.nextElement();
                if (!nic.isLoopback()) {
                    result.add(nic);
                }
            }
        } catch (SocketException e) {
            Log.error("Wifi", "WifiService: could not enumerate interfaces: " + e.getMessage());
        }
        return result;
    }

    // =========================================================================
    // Scan / connect
    // =========================================================================

    /** True when NetworkManager's CLI is on this machine (cached after first probe). */
    public boolean available() {
        Boolean cached = available;
        if (cached == null) {
            cached = run(List.of("nmcli", "--version")).success();
            available = cached;
            if (!cached) {
                Log.info("Wifi", "WifiService: nmcli not available; on-screen WiFi setup disabled.");
            }
        }
        return cached;
    }

    /**
     * Visible networks, strongest first with the active one pinned to the top.
     * Blocks for the duration of the scan — call it off the FX thread.
     */
    public List<WifiNetwork> scanNetworks() {
        if (!available()) {
            return List.of();
        }
        CommandResult result = run(List.of("nmcli", "-t", "-f", "IN-USE,SSID,SIGNAL,SECURITY",
                "device", "wifi", "list", "--rescan", "yes"));
        if (!result.success()) {
            Log.error("Wifi", "WifiService: scan failed: " + result.output());
            return List.of();
        }
        return parseScan(result.output());
    }

    /**
     * Parses {@code nmcli -t} scan output ({@code IN-USE:SSID:SIGNAL:SECURITY}).
     * Terse mode escapes literal colons as {@code \:}, which password-protected
     * SSIDs like {@code "Casa:Wifi"} do contain.
     */
    static List<WifiNetwork> parseScan(String terseOutput) {
        Map<String, WifiNetwork> bySsid = new LinkedHashMap<>();
        if (terseOutput == null || terseOutput.isBlank()) {
            return List.of();
        }
        for (String line : terseOutput.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            List<String> fields = splitTerse(line);
            if (fields.size() < 4) {
                continue;
            }
            String ssid = fields.get(1).trim();
            if (ssid.isEmpty()) {
                continue; // hidden network
            }
            boolean active = "*".equals(fields.get(0).trim());
            int signal = parseSignal(fields.get(2));
            String security = fields.get(3).trim();

            // The same SSID appears once per access point: keep the best signal.
            WifiNetwork existing = bySsid.get(ssid);
            if (existing == null || signal > existing.signal()) {
                bySsid.put(ssid, new WifiNetwork(ssid, signal, security,
                        active || (existing != null && existing.active())));
            }
        }
        List<WifiNetwork> networks = new ArrayList<>(bySsid.values());
        networks.sort(Comparator.comparing(WifiNetwork::active).reversed()
                .thenComparing(Comparator.comparingInt(WifiNetwork::signal).reversed())
                .thenComparing(WifiNetwork::ssid, String.CASE_INSENSITIVE_ORDER));
        return networks;
    }

    private static int parseSignal(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Splits one terse {@code nmcli} line, honouring backslash escapes. */
    static List<String> splitTerse(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\' && i + 1 < line.length()) {
                current.append(line.charAt(++i));
            } else if (c == ':') {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields;
    }

    /**
     * Joins a network. A blank password joins without one (open or already
     * saved network). Blocks for up to {@link #TIMEOUT_SECONDS} — call it off
     * the FX thread.
     */
    public ConnectResult connect(String ssid, String password) {
        if (ssid == null || ssid.isBlank()) {
            return new ConnectResult(false, "Red no v\u00e1lida");
        }
        if (!available()) {
            return new ConnectResult(false, "NetworkManager no est\u00e1 disponible en este dispositivo");
        }
        List<String> args = new ArrayList<>(List.of("nmcli", "--wait", "30", "device", "wifi", "connect", ssid));
        if (password != null && !password.isBlank()) {
            args.add("password");
            args.add(password);
        }

        CommandResult result = run(args);
        if (result.success()) {
            Log.info("Wifi", "WifiService: connected to " + ssid);
            return new ConnectResult(true, "Conectada a " + ssid);
        }
        String detail = firstMeaningfulLine(result.output());
        Log.error("Wifi", "WifiService: connection to " + ssid + " failed: " + detail);
        return new ConnectResult(false, detail.isBlank() ? "No se pudo conectar" : detail);
    }

    /**
     * Name of the active WiFi connection, or null when there is none.
     * Blocks briefly — call it off the FX thread.
     */
    public String activeSsid() {
        if (!available()) {
            return null;
        }
        CommandResult result = run(List.of("nmcli", "-t", "-f", "IN-USE,SSID",
                "device", "wifi", "list", "--rescan", "no"));
        if (!result.success()) {
            return null;
        }
        for (String line : result.output().split("\\R")) {
            List<String> fields = splitTerse(line);
            if (fields.size() >= 2 && "*".equals(fields.get(0).trim())) {
                String ssid = fields.get(1).trim();
                return ssid.isEmpty() ? null : ssid;
            }
        }
        return null;
    }

    private static String firstMeaningfulLine(String output) {
        if (output == null) {
            return "";
        }
        for (String line : output.split("\\R")) {
            String trimmed = line.replaceFirst("^Error:\\s*", "").trim();
            if (!trimmed.isEmpty()) {
                return trimmed.length() > 120 ? trimmed.substring(0, 120) + "\u2026" : trimmed;
            }
        }
        return "";
    }

    private record CommandResult(boolean success, String output) {}

    /** Runs a command with a hard timeout; failures come back as a result, never as an exception. */
    private static CommandResult run(List<String> args) {
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(args);
            builder.redirectErrorStream(true);
            process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new CommandResult(false, "El comando tard\u00f3 demasiado");
            }
            return new CommandResult(process.exitValue() == 0, output);
        } catch (IOException e) {
            return new CommandResult(false, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CommandResult(false, "Interrumpido");
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
}
