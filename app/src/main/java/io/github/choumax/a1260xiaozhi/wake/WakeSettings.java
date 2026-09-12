package io.github.choumax.a1260xiaozhi.wake;

import android.content.Context;
import android.content.SharedPreferences;

/** Separate opt-in preferences; never changes connection or session settings. */
public final class WakeSettings {
    public static final String DEFAULT_KEYWORD = "你好小智";
    private final SharedPreferences preferences;
    public WakeSettings(Context context) {
        preferences = context.getSharedPreferences("local_wake", Context.MODE_PRIVATE);
        if (!preferences.getBoolean("threshold_recovery_v2", false)) {
            SharedPreferences.Editor edit = preferences.edit().putBoolean("threshold_recovery_v2", true);
            // v1 forcibly raised existing installations to 90%, leaving virtually no
            // margin between the user's own accepted recordings. Preserve other choices.
            if (preferences.getBoolean("strict_match_v1", false)
                    && Math.abs(preferences.getFloat("threshold", 0.90f) - 0.90f) < 0.0001f) {
                edit.putFloat("threshold", (float) WakeMatcher.DEFAULT_THRESHOLD);
            }
            edit.apply();
        }
    }
    public boolean isEnabled() { return preferences.getBoolean("enabled", false); }
    public void setEnabled(boolean enabled) {
        preferences.edit().putBoolean("enabled", enabled).apply();
        if (!enabled) WakeKeywordVerifier.discardCached();
    }
    public String keyword() {
        String value = preferences.getString("kws_keyword", DEFAULT_KEYWORD);
        return WakeKeywordValidator.validate(value) == WakeKeywordValidator.Result.VALID ? value : DEFAULT_KEYWORD;
    }
    public void setKeyword(String keyword) {
        String valid = WakeKeywordValidator.requireValid(keyword);
        String previous = preferences.getString("kws_keyword", DEFAULT_KEYWORD);
        SharedPreferences.Editor edit = preferences.edit().putString("kws_keyword", valid);
        if (!valid.equals(previous)) edit.remove("kws_verified_keyword");
        edit.apply();
    }
    public boolean isKeywordVerified() { return keyword().equals(preferences.getString("kws_verified_keyword", null)); }
    public void markKeywordVerified() { preferences.edit().putString("kws_verified_keyword", keyword()).apply(); }
    public int sensitivity() {
        int value = preferences.getInt("kws_sensitivity", WakeSensitivity.DEFAULT);
        return value >= 0 && value <= 100 ? value : WakeSensitivity.DEFAULT;
    }
    public void setSensitivity(int value) {
        WakeSensitivity.threshold(value);
        preferences.edit().putInt("kws_sensitivity", value).apply();
    }
    public float keywordThreshold() { return WakeSensitivity.threshold(sensitivity()); }
    public double threshold() {
        float value = preferences.getFloat("threshold", (float) WakeMatcher.DEFAULT_THRESHOLD);
        return Float.isFinite(value) && value >= WakeMatcher.MIN_THRESHOLD && value <= WakeMatcher.MAX_THRESHOLD
                ? value : WakeMatcher.DEFAULT_THRESHOLD;
    }
    public void setThreshold(double threshold) {
        if (!Double.isFinite(threshold) || threshold < WakeMatcher.MIN_THRESHOLD || threshold > WakeMatcher.MAX_THRESHOLD)
            throw new IllegalArgumentException("Invalid wake threshold");
        preferences.edit().putFloat("threshold", (float) threshold).apply();
    }
}
