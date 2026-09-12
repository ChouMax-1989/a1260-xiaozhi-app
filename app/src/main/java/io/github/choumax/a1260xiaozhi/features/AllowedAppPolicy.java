package io.github.choumax.a1260xiaozhi.features;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/** Pure validation for explicitly user-selected launcher packages. No wildcards or components. */
public final class AllowedAppPolicy {
    public static final int MAX_APPS = 16;
    private AllowedAppPolicy() { }
    public static boolean validPackage(String value) {
        return value != null && value.length() <= 255
                && value.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");
    }
    public static Set<String> validate(Collection<String> selected) {
        if (selected == null || selected.size() > MAX_APPS) throw new IllegalArgumentException("Invalid app selection");
        Set<String> result = new TreeSet<>();
        for (String pkg : selected) {
            if (!validPackage(pkg)) throw new IllegalArgumentException("Invalid app package");
            result.add(pkg);
        }
        return Collections.unmodifiableSet(result);
    }
    public static boolean permits(Collection<String> selected, Collection<String> launchable, String pkg) {
        return validPackage(pkg) && selected != null && launchable != null
                && selected.contains(pkg) && launchable.contains(pkg);
    }
}
