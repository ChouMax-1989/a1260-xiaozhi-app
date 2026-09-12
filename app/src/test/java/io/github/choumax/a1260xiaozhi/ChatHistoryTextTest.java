package io.github.choumax.a1260xiaozhi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import io.github.choumax.a1260xiaozhi.chat.ChatHistoryStore;
import org.junit.Test;

public class ChatHistoryTextTest {
    @Test public void trimsWhitespaceAndBoundsStoredMessageSize() {
        assertEquals("hello", ChatHistoryStore.trim("  hello  "));
        String longText = "x".repeat(2000);
        assertEquals(1600, ChatHistoryStore.trim(longText).length());
        assertTrue(ChatHistoryStore.trim(null).isEmpty());
    }
}
