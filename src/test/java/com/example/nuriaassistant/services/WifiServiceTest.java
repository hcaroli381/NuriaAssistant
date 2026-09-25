package com.example.nuriaassistant.services;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the pieces of the WiFi setup that must not depend on NetworkManager
 * being installed: the MAC formatting, the terse nmcli parser and its escaping
 * (SSIDs really do contain colons and backslashes).
 */
public class WifiServiceTest {

    @Test
    void testFormatMacUppercasesAndSeparates() {
        byte[] raw = {(byte) 0xb8, 0x27, (byte) 0xeb, 0x00, 0x1a, (byte) 0xf3};
        assertEquals("B8:27:EB:00:1A:F3", WifiService.formatMac(raw));
    }

    @Test
    void testFormatMacRejectsWrongLengths() {
        assertNull(WifiService.formatMac(null));
        assertNull(WifiService.formatMac(new byte[]{1, 2, 3}));
        assertNull(WifiService.formatMac(new byte[8]));
    }

    @Test
    void testParseScanReadsFieldsAndSortsActiveFirst() {
        String output = String.join("\n",
                ":Vecina:44:WPA2",
                "*:Casa:72:WPA2",
                ":Otra:61:--",
                ":Vecina:88:WPA2",   // second access point of the same SSID, stronger
                ":    :30:WPA2",     // blank SSID (hidden network) must be skipped
                "");

        List<WifiService.WifiNetwork> networks = WifiService.parseScan(output);

        assertEquals(3, networks.size(), "Hidden network skipped, duplicate SSIDs merged");
        assertEquals("Casa", networks.get(0).ssid(), "The active network comes first");
        assertTrue(networks.get(0).active());
        // Duplicate SSID keeps the strongest signal of its access points.
        assertEquals("Vecina", networks.get(1).ssid());
        assertEquals(88, networks.get(1).signal());
        assertTrue(networks.get(2).isOpen(), "'--' means an open network");
    }

    @Test
    void testParseScanUnescapesColonsAndBackslashes() {
        // nmcli terse mode escapes a literal ':' as '\:' and '\' as '\\'.
        String output = "*:Casa\\:Wifi:70:WPA1 WPA2\n:Back\\\\slash:55:";

        List<WifiService.WifiNetwork> networks = WifiService.parseScan(output);

        assertEquals(2, networks.size());
        assertEquals("Casa:Wifi", networks.get(0).ssid());
        assertEquals("WPA1 WPA2", networks.get(0).security());
        assertEquals("Back\\slash", networks.get(1).ssid());
        assertTrue(networks.get(1).isOpen(), "Empty security field means open");
    }

    @Test
    void testParseScanToleratesEmptyAndTruncatedOutput() {
        assertTrue(WifiService.parseScan(null).isEmpty());
        assertTrue(WifiService.parseScan("").isEmpty());
        assertTrue(WifiService.parseScan("   ").isEmpty());
        assertTrue(WifiService.parseScan(":RedSinSenal").isEmpty(), "Truncated row is skipped");
    }

    @Test
    void testSplitTerseKeepsEmptyTrailingFields() {
        assertEquals(List.of("*", "Casa", "72", "WPA2"), WifiService.splitTerse("*:Casa:72:WPA2"));
        assertEquals(List.of("", "", ""), WifiService.splitTerse("::"));
    }
}
