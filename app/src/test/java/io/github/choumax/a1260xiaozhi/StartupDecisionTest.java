package io.github.choumax.a1260xiaozhi;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class StartupDecisionTest {
    @Test public void officialIssuedConfigReconnectsAfterProcessRestart() {
        assertEquals(StartupDecision.Action.CONNECT, StartupDecision.decide(false, true));
    }

    @Test public void freshOfficialInstallActivatesAndCustomServerConnects() {
        assertEquals(StartupDecision.Action.ACTIVATE, StartupDecision.decide(false, false));
        assertEquals(StartupDecision.Action.CONNECT, StartupDecision.decide(true, false));
    }
}
