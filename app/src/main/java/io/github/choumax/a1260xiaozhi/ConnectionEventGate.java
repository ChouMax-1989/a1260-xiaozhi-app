package io.github.choumax.a1260xiaozhi;

import java.util.concurrent.atomic.AtomicBoolean;

/** One terminal network event per attempt; a stale connection cannot terminate its replacement. */
final class ConnectionEventGate {
    private final long attempt;
    private final AtomicBoolean terminal = new AtomicBoolean();
    ConnectionEventGate(long attempt) { this.attempt = attempt; }
    boolean isOpen(long current) { return attempt == current && !terminal.get(); }
    boolean terminate(long current) { return attempt == current && terminal.compareAndSet(false, true); }
}
