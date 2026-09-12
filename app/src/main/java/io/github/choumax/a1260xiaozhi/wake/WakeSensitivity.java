package io.github.choumax.a1260xiaozhi.wake;

/** User-facing scale: larger values lower the keyword posterior threshold. */
final class WakeSensitivity {
    static final int DEFAULT = 80;
    static float threshold(int sensitivity) {
        if (sensitivity < 0 || sensitivity > 100) throw new IllegalArgumentException("Invalid sensitivity");
        return (110 - sensitivity) / 200f;
    }
}
