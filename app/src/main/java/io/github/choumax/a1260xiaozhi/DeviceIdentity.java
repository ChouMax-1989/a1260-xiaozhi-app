package io.github.choumax.a1260xiaozhi;

import java.security.SecureRandom;
import java.util.Locale;

/** Generates a random, locally-administered unicast MAC-looking identifier; never reads hardware identity. */
public final class DeviceIdentity {
    private DeviceIdentity() { }
    public static String createLocallyAdministeredMac() { byte[] bytes = new byte[6]; new SecureRandom().nextBytes(bytes); bytes[0] = (byte) ((bytes[0] & 0xfe) | 0x02); return formatMac(bytes); }
    public static boolean isLocallyAdministeredMac(String value) { if (value == null || !value.matches("[0-9a-f]{2}(:[0-9a-f]{2}){5}")) return false; int first = Integer.parseInt(value.substring(0, 2), 16); return (first & 0x03) == 0x02; }
    static String formatMac(byte[] bytes) { if (bytes == null || bytes.length != 6) throw new IllegalArgumentException("Expected six bytes."); return String.format(Locale.ROOT, "%02x:%02x:%02x:%02x:%02x:%02x", bytes[0] & 0xff, bytes[1] & 0xff, bytes[2] & 0xff, bytes[3] & 0xff, bytes[4] & 0xff, bytes[5] & 0xff); }
}
