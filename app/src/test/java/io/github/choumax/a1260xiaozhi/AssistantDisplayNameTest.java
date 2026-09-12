package io.github.choumax.a1260xiaozhi;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class AssistantDisplayNameTest {
    @Test public void keepsAConfiguredNameAndTrimsOuterWhitespace() {
        assertEquals("周三", AssistantDisplayName.normalize("  周三  "));
    }

    @Test public void usesNeutralFallbackForMissingNames() {
        assertEquals("助手", AssistantDisplayName.normalize(null));
        assertEquals("助手", AssistantDisplayName.normalize(" \n\t "));
    }

    @Test public void capsByUnicodeCodePointWithoutSplittingEmoji() {
        String longName = "1234567890123456789😀尾";
        assertEquals("1234567890123456789😀", AssistantDisplayName.normalize(longName));
    }
}
