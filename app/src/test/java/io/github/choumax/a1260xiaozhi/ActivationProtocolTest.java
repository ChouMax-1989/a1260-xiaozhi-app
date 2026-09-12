package io.github.choumax.a1260xiaozhi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class ActivationProtocolTest {
    @Test public void parsesBindingWithoutTreatingIssuedConfigAsBound() throws Exception {
        ActivationResponse response = ActivationResponse.parse("{\"activation\":{\"message\":\"bind\",\"code\":\"001234\",\"challenge\":\"c\",\"timeout_ms\":9000},\"websocket\":{\"url\":\"wss://service/v1/\",\"token\":\"secret\"}}");
        assertEquals("001234", response.code); assertEquals(9000, response.timeoutMs); assertTrue(response.hasActivation()); assertTrue(response.hasChallenge()); assertTrue(response.hasValidWebsocketV1());
        assertEquals(ActivationState.WAITING_USER_BIND, ActivationReducer.next(ActivationState.CHECKING_OTA, ActivationReducer.Event.OTA_BINDING));
    }
    @Test public void rejectsUnboundTransportAndFollows202Then200Recheck() throws Exception {
        ActivationResponse response = ActivationResponse.parse("{\"websocket\":{\"url\":\"ws://insecure\",\"token\":\"x\",\"version\":1}}");
        assertFalse(response.hasActivation()); assertFalse(response.hasValidWebsocketV1());
        assertEquals(ActivationState.POLLING_ACTIVATE, ActivationReducer.next(ActivationState.WAITING_USER_BIND, ActivationReducer.Event.ACTIVATE_202));
        assertEquals(ActivationState.RECHECKING_OTA, ActivationReducer.next(ActivationState.POLLING_ACTIVATE, ActivationReducer.Event.ACTIVATE_200));
    }
}
