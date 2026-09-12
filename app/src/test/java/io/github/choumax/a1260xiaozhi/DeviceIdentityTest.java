package io.github.choumax.a1260xiaozhi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class DeviceIdentityTest {
    @Test public void formatsLocallyAdministeredMac() {
        String id = DeviceIdentity.formatMac(new byte[] {2, 17, 34, 51, 68, 85});
        assertEquals("02:11:22:33:44:55", id);
        assertTrue(DeviceIdentity.isLocallyAdministeredMac(id));
        String generated = DeviceIdentity.createLocallyAdministeredMac();
        assertTrue(generated.matches("[0-9a-f]{2}(:[0-9a-f]{2}){5}"));
        assertEquals(2, Integer.parseInt(generated.substring(0, 2), 16) & 3);
    }
}
