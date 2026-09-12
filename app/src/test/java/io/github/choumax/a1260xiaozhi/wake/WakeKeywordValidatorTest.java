package io.github.choumax.a1260xiaozhi.wake;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class WakeKeywordValidatorTest {
    @Test public void acceptsTwoToTwelveChineseCharacters() {
        assertEquals(WakeKeywordValidator.Result.VALID, WakeKeywordValidator.validate("你好"));
        assertEquals(WakeKeywordValidator.Result.VALID, WakeKeywordValidator.validate("你好小智"));
        assertEquals(WakeKeywordValidator.Result.VALID, WakeKeywordValidator.validate("一二三四五六七八九十天地"));
        assertEquals(WakeKeywordValidator.Result.VALID, WakeKeywordValidator.validate("𠀀好"));
    }

    @Test public void rejectsMissingAndOutOfRangeKeywords() {
        assertEquals(WakeKeywordValidator.Result.EMPTY, WakeKeywordValidator.validate(null));
        assertEquals(WakeKeywordValidator.Result.EMPTY, WakeKeywordValidator.validate(""));
        assertEquals(WakeKeywordValidator.Result.TOO_SHORT, WakeKeywordValidator.validate("你"));
        assertEquals(WakeKeywordValidator.Result.TOO_LONG, WakeKeywordValidator.validate("一二三四五六七八九十天地人"));
    }

    @Test public void rejectsPinyinEnglishDigitsSpacesAndPunctuation() {
        assertEquals(WakeKeywordValidator.Result.NON_HAN, WakeKeywordValidator.validate("nihao"));
        assertEquals(WakeKeywordValidator.Result.NON_HAN, WakeKeywordValidator.validate("hello"));
        assertEquals(WakeKeywordValidator.Result.NON_HAN, WakeKeywordValidator.validate("你好2"));
        assertEquals(WakeKeywordValidator.Result.NON_HAN, WakeKeywordValidator.validate("你好 小智"));
        assertEquals(WakeKeywordValidator.Result.NON_HAN, WakeKeywordValidator.validate("你好！"));
    }
}
