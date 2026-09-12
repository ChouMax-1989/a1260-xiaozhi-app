package io.github.choumax.a1260xiaozhi.wake;

/** Pure validation for the user-visible Chinese wake phrase. */
public final class WakeKeywordValidator {
    public static final int MIN_CHARACTERS = 2;
    public static final int MAX_CHARACTERS = 12;

    public enum Result { VALID, EMPTY, TOO_SHORT, TOO_LONG, NON_HAN }

    private WakeKeywordValidator() { }

    public static Result validate(String keyword) {
        if (keyword == null || keyword.isEmpty()) return Result.EMPTY;
        int count = keyword.codePointCount(0, keyword.length());
        if (count < MIN_CHARACTERS) return Result.TOO_SHORT;
        if (count > MAX_CHARACTERS) return Result.TOO_LONG;
        for (int offset = 0; offset < keyword.length();) {
            int codePoint = keyword.codePointAt(offset);
            if (Character.UnicodeScript.of(codePoint) != Character.UnicodeScript.HAN) return Result.NON_HAN;
            offset += Character.charCount(codePoint);
        }
        return Result.VALID;
    }

    public static String requireValid(String keyword) {
        if (validate(keyword) != Result.VALID) throw new IllegalArgumentException("Invalid Chinese wake keyword");
        return keyword;
    }
}
