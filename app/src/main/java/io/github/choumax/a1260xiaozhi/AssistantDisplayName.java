package io.github.choumax.a1260xiaozhi;

/** Normalizes the user-selected local chat title without implying a server-provided identity. */
public final class AssistantDisplayName {
    public static final String DEFAULT = "助手";
    static final int MAX_CODE_POINTS = 20;

    private AssistantDisplayName() { }

    public static String normalize(String value) {
        if (value == null) return DEFAULT;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return DEFAULT;
        int count = trimmed.codePointCount(0, trimmed.length());
        return count <= MAX_CODE_POINTS ? trimmed : trimmed.substring(0, trimmed.offsetByCodePoints(0, MAX_CODE_POINTS));
    }
}
