package io.github.choumax.a1260xiaozhi;

import static org.junit.Assert.*;
import org.junit.Test;

public final class ConnectionEventGateTest {
    @Test public void closingClosedThenFailureIsOnlyOneTerminalEvent() {
        ConnectionEventGate gate = new ConnectionEventGate(7);
        assertTrue(gate.isOpen(7)); assertTrue(gate.terminate(7));
        assertFalse(gate.terminate(7)); assertFalse(gate.terminate(7)); assertFalse(gate.isOpen(7));
    }
    @Test public void staleConnectionCannotCloseTheNewAttempt() {
        ConnectionEventGate old = new ConnectionEventGate(7), next = new ConnectionEventGate(8);
        assertFalse(old.terminate(8)); assertFalse(old.isOpen(8));
        assertTrue(next.isOpen(8)); assertTrue(next.terminate(8));
    }
}
