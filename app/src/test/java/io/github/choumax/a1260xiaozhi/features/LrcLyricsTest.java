package io.github.choumax.a1260xiaozhi.features;

import org.junit.Test;
import static org.junit.Assert.*;

public final class LrcLyricsTest {
    @Test public void timestampsSortAndLookupDoesNotShowLyricsEarly() {
        LrcLyrics lyrics = LrcLyrics.parse("[00:02.50]second\n[00:01.2]first\n[00:03.123]third");
        assertEquals("", lyrics.textAt(1199));
        assertEquals("first", lyrics.textAt(1200));
        assertEquals("second", lyrics.textAt(2500));
        assertEquals("third", lyrics.textAt(3123));
        assertEquals("first", lyrics.textAt(1300)); // seeking backward
    }
    @Test public void offsetAfterLyricsIsGlobalAndPositiveAdvances() {
        LrcLyrics lyrics = LrcLyrics.parse("[00:01.000]one\n[offset:+200]\n[00:02]two");
        assertEquals(800, lyrics.lines.get(0).timeMs);
        assertEquals("one", lyrics.textAt(800));
        assertEquals("two", lyrics.textAt(1800));
        assertEquals(1200, LrcLyrics.parse("[offset:-200]\n[00:01]one").lines.get(0).timeMs);
    }
    @Test public void multiStampBomAndBilingualLinesArePreserved() {
        LrcLyrics lyrics = LrcLyrics.parse("\ufeff[ti:ignored]\r\n[00:01][00:03]repeat\r\n[00:01]翻译");
        assertEquals(2, lyrics.lines.size());
        assertEquals("repeat\n翻译", lyrics.textAt(1000));
        assertEquals("repeat", lyrics.textAt(3000));
    }
    @Test public void emptyTimestampCanClearAndMalformedTimesAreIgnored() {
        LrcLyrics lyrics = LrcLyrics.parse("[00:00]first\n[00:01]\n[00:99]bad\n[abc]bad\nplain text");
        assertEquals(2, lyrics.lines.size());
        assertEquals("", lyrics.textAt(1000));
        assertEquals(-1, lyrics.indexAt(-1));
    }
    @Test public void boundsPreventOversizeAndTimestampExpansion() {
        assertThrows(IllegalArgumentException.class, () -> LrcLyrics.parse("x".repeat(LrcLyrics.MAX_CHARS + 1)));
        assertThrows(IllegalArgumentException.class, () -> LrcLyrics.parse("[00:01]" + "x".repeat(4097)));
        String expanded = "[00:01]".repeat(100) + "x".repeat(2000);
        assertThrows(IllegalArgumentException.class, () -> LrcLyrics.parse(expanded));
        assertThrows(IllegalArgumentException.class, () -> LrcLyrics.parse("[00:01]x\n".repeat(4097)));
    }
    @Test public void offsetClampsBeforeZeroAndReadOnlyResultCannotBeChanged() {
        LrcLyrics lyrics = LrcLyrics.parse("[offset:5000]\n[00:01]one");
        assertEquals(0, lyrics.lines.get(0).timeMs);
        assertThrows(UnsupportedOperationException.class, () -> lyrics.lines.clear());
    }
}
