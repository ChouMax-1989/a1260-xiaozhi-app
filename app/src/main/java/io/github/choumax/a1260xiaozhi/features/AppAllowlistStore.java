package io.github.choumax.a1260xiaozhi.features;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Private, empty-by-default allowlist. Only explicit, currently exported launcher activities. */
public final class AppAllowlistStore {
    private final Context context;
    private final SharedPreferences preferences;
    AppAllowlistStore(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences("selected_launcher_apps", Context.MODE_PRIVATE);
    }
    public static final class App {
        public final String packageName, label;
        final ComponentName component;
        App(String pkg, String label, ComponentName component) { this.packageName = pkg; this.label = label; this.component = component; }
    }
    public synchronized List<App> launchableApps() {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        PackageManager manager = context.getPackageManager();
        Map<String, App> result = new TreeMap<>();
        for (ResolveInfo info : manager.queryIntentActivities(query, 0)) {
            ActivityInfo activity = info.activityInfo;
            if (activity == null || !activity.exported || !activity.enabled || !activity.applicationInfo.enabled
                    || !AllowedAppPolicy.validPackage(activity.packageName) || context.getPackageName().equals(activity.packageName)) continue;
            CharSequence label = info.loadLabel(manager);
            result.putIfAbsent(activity.packageName, new App(activity.packageName,
                    label == null ? activity.packageName : label.toString(), new ComponentName(activity.packageName, activity.name)));
        }
        return new ArrayList<>(result.values());
    }
    public synchronized Set<String> selectedPackages() {
        Set<String> stored = preferences.getStringSet("packages", Collections.emptySet());
        try { return AllowedAppPolicy.validate(stored); }
        catch (IllegalArgumentException invalid) { return Collections.emptySet(); }
    }
    public synchronized void select(String pkg, boolean enabled) {
        Set<String> selected = new TreeSet<>(selectedPackages());
        if (enabled) {
            boolean found = false;
            for (App app : launchableApps()) if (app.packageName.equals(pkg)) found = true;
            if (!found) throw new IllegalArgumentException("App is not launchable");
            selected.add(pkg);
        } else selected.remove(pkg);
        preferences.edit().putStringSet("packages", new TreeSet<>(AllowedAppPolicy.validate(selected))).apply();
    }
    public synchronized List<String> allowedPackages() {
        Set<String> selected = selectedPackages();
        List<String> result = new ArrayList<>();
        for (App app : launchableApps()) if (selected.contains(app.packageName)) result.add(app.packageName);
        return result;
    }
    public synchronized JSONObject launch(String pkg) {
        if (!AllowedAppPolicy.validPackage(pkg) || !selectedPackages().contains(pkg)) throw new SecurityException("App not selected");
        for (App app : launchableApps()) {
            if (app.packageName.equals(pkg)) {
                Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                        .setComponent(app.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                context.startActivity(intent);
                return McpDispatcher.object("status", "launch_requested", "display_confirmed", false);
            }
        }
        throw new IllegalStateException("Selected app is no longer launchable");
    }
}
