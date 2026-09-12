package io.github.choumax.a1260xiaozhi;

import static org.junit.Assert.*;
import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLHandshakeException;
import org.junit.Test;

public final class ConnectionDiagnosticTest {
    private ConnectionDiagnostic classify(Throwable t) { return ConnectionDiagnostic.failure(t, 0, SessionState.READY); }
    @Test public void dnsTlsAndSocketTimeoutAreDifferent() {
        assertEquals(ConnectionDiagnostic.Reason.DNS_FAILURE, classify(new IOException("outer", new UnknownHostException("private-host"))).reason);
        assertEquals(ConnectionDiagnostic.Reason.TLS_FAILURE, classify(new SSLHandshakeException("certificate secret-host")).reason);
        assertEquals(ConnectionDiagnostic.Reason.SOCKET_TIMEOUT, classify(new SocketTimeoutException("timeout on private url")).reason);
        assertEquals(ConnectionDiagnostic.Reason.CONNECT_FAILURE, classify(new ConnectException("private ip refused")).reason);
        assertEquals(ConnectionDiagnostic.Reason.SOCKET_FAILURE, classify(new EOFException("private endpoint")).reason);
    }
    @Test public void knownOkHttpPingTimeoutHasItsOwnReason() {
        ConnectionDiagnostic d = classify(new SocketTimeoutException("sent ping but didn't receive pong within 30000ms (after 2 successful ping/pongs)"));
        assertEquals(ConnectionDiagnostic.Reason.PING_TIMEOUT, d.reason);
        assertEquals(ConnectionDiagnostic.Recovery.RETRY_BACKOFF, d.recovery);
        assertFalse(d.summary.contains("successful"));
        assertEquals(ConnectionDiagnostic.Reason.SOCKET_FAILURE, classify(new IOException("sent ping but didn't receive pong within injected")).reason);
    }
    @Test public void authFailuresAreNeverAutomaticRetryHints() {
        ConnectionDiagnostic a = ConnectionDiagnostic.failure(new IOException("secret"), 401, SessionState.CONNECTING);
        ConnectionDiagnostic b = ConnectionDiagnostic.failure(null, 403, SessionState.CONNECTING);
        assertEquals(ConnectionDiagnostic.Reason.HTTP_UNAUTHORIZED, a.reason);
        assertEquals(ConnectionDiagnostic.Reason.HTTP_FORBIDDEN, b.reason);
        assertEquals(ConnectionDiagnostic.Recovery.USER_ACTION, a.recovery);
        assertEquals(ConnectionDiagnostic.Recovery.USER_ACTION, b.recovery);
        assertEquals(401, a.httpStatus);
    }
    @Test public void serverErrorsAndBadRoutesHaveDifferentRetryHints() {
        assertEquals(ConnectionDiagnostic.Recovery.RETRY_BACKOFF, ConnectionDiagnostic.failure(null, 503, SessionState.CONNECTING).recovery);
        assertEquals(ConnectionDiagnostic.Recovery.RETRY_BACKOFF, ConnectionDiagnostic.failure(null, 429, SessionState.CONNECTING).recovery);
        assertEquals(ConnectionDiagnostic.Recovery.USER_ACTION, ConnectionDiagnostic.failure(null, 404, SessionState.CONNECTING).recovery);
        assertEquals(ConnectionDiagnostic.Recovery.USER_ACTION, classify(new SSLHandshakeException("bad cert")).recovery);
    }
    @Test public void normalIdleClosureIsDisconnectedNotErrorOrRetryLoop() {
        for (int code : new int[]{1000, 1001, 1005}) {
            ConnectionDiagnostic d = ConnectionDiagnostic.remoteClose(code, SessionState.READY);
            assertEquals(ConnectionDiagnostic.Reason.NORMAL_IDLE_CLOSE, d.reason);
            assertEquals(SessionState.DISCONNECTED, d.nextState);
            assertEquals(ConnectionDiagnostic.Recovery.ON_DEMAND, d.recovery);
            assertFalse(d.isError);
        }
    }
    @Test public void activeClosureIsNotMisreportedAsIdle() {
        ConnectionDiagnostic d = ConnectionDiagnostic.remoteClose(1000, SessionState.LISTENING);
        assertEquals(ConnectionDiagnostic.Reason.SESSION_INTERRUPTED, d.reason);
        assertEquals(SessionState.DISCONNECTED, d.nextState);
        assertEquals(SessionState.LISTENING, d.previousState);
    }
    @Test public void abnormalCloseAndUserCloseRemainDistinct() {
        ConnectionDiagnostic abnormal = ConnectionDiagnostic.remoteClose(1011, SessionState.READY);
        assertTrue(abnormal.isError); assertEquals(SessionState.ERROR, abnormal.nextState);
        assertEquals(ConnectionDiagnostic.Recovery.RETRY_BACKOFF, abnormal.recovery);
        assertEquals(ConnectionDiagnostic.Recovery.USER_ACTION, ConnectionDiagnostic.remoteClose(1008, SessionState.READY).recovery);
        ConnectionDiagnostic user = ConnectionDiagnostic.event(ConnectionDiagnostic.Reason.USER_DISCONNECTED, SessionState.READY);
        assertFalse(user.isError); assertEquals(ConnectionDiagnostic.Recovery.NONE, user.recovery);
    }
    @Test public void allExternalDetailsAreDiscarded() {
        String secret = "wss://secret.example/?token=ABC device-id=PRIVATE my-chat-body";
        ConnectionDiagnostic d = classify(new IOException(secret, new UnknownHostException(secret)));
        assertFalse(d.summary.contains("secret")); assertFalse(d.toString().contains("ABC"));
        assertFalse(d.summary.contains("PRIVATE")); assertFalse(d.summary.contains("my-chat-body"));
        assertEquals(ConnectionDiagnostic.Reason.UNKNOWN_FAILURE, classify(null).reason);
    }
    @Test public void causeTraversalIsBoundedForCycles() {
        IOException a = new IOException("a"), b = new IOException("b"); a.initCause(b); b.initCause(a);
        assertEquals(ConnectionDiagnostic.Reason.SOCKET_FAILURE, classify(a).reason);
    }
    @Test public void helloTimeoutIsNotSocketOrPingTimeout() {
        ConnectionDiagnostic d = ConnectionDiagnostic.event(ConnectionDiagnostic.Reason.HELLO_TIMEOUT, SessionState.HELLO_WAIT);
        assertEquals(ConnectionDiagnostic.Reason.HELLO_TIMEOUT, d.reason);
        assertEquals(ConnectionDiagnostic.Recovery.RETRY_BACKOFF, d.recovery);
    }
}
