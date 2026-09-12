package io.github.choumax.a1260xiaozhi;

import java.io.IOException;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public final class OpusPlaybackPacketTest {
    @Test public void queueBudgetUsesActualTwentyAndSixtyMsPackets() throws IOException {
        assertEquals(20_000, OpusPlayback.packetDurationUs(new byte[]{(byte) 0x98}));
        assertEquals(60_000, OpusPlayback.packetDurationUs(new byte[]{0x18}));
        assertEquals(120_000, OpusPlayback.packetDurationUs(new byte[]{0x19}));
    }
    @Test(expected = IOException.class) public void invalidExtendedCountIsRejected() throws IOException {
        OpusPlayback.packetDurationUs(new byte[]{0x1b, 3});
    }
    @Test(expected = IOException.class) public void emptyPacketIsRejected() throws IOException {
        OpusPlayback.packetDurationUs(new byte[0]);
    }
    @Test(expected = IOException.class) public void zeroFramePacketIsRejected() throws IOException {
        OpusPlayback.packetDurationUs(new byte[]{(byte) 0x9b, 0});
    }
}
