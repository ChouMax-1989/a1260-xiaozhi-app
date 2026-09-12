package io.github.choumax.a1260xiaozhi.features;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;
import java.util.Set;
import io.github.choumax.a1260xiaozhi.R;

/** Local-only user consent surface; it never launches apps simply by listing/selecting them. */
public final class AllowedAppsActivity extends Activity {
    private AppAllowlistStore store;
    private TextView status;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); setTitle(R.string.apps_title);
        store = FeatureRuntime.get(this).appAllowlist();
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(22, 22, 22, 22); scroll.addView(root);
        TextView help = new TextView(this); help.setText(R.string.apps_help); help.setTextSize(17); root.addView(help);
        status = new TextView(this); root.addView(status);
        status.setId(R.id.apps_count);
        List<AppAllowlistStore.App> apps = store.launchableApps();
        Set<String> selected = store.selectedPackages();
        for (AppAllowlistStore.App app : apps) {
            CheckBox check = new CheckBox(this); check.setText(app.label + "\n" + app.packageName); check.setMinHeight(Math.round(56 * getResources().getDisplayMetrics().density));
            check.setChecked(selected.contains(app.packageName));
            check.setOnCheckedChangeListener((view, checked) -> {
                try { store.select(app.packageName, checked); count(); }
                catch (IllegalArgumentException failure) { check.setChecked(false); status.setText(R.string.apps_limit); }
            });
            root.addView(check);
        }
        if (apps.isEmpty()) { TextView empty = new TextView(this); empty.setText(R.string.apps_empty); root.addView(empty); }
        Button clear = new Button(this); clear.setText(R.string.apps_clear); clear.setOnClickListener(view -> {
            for (String pkg : store.selectedPackages()) store.select(pkg, false);
            recreate();
        }); root.addView(clear);
        clear.setId(R.id.apps_clear);
        Button close = new Button(this); close.setText(R.string.feature_close); close.setOnClickListener(view -> finish()); root.addView(close);
        count(); setContentView(scroll);
    }
    private void count() { status.setText(getString(R.string.apps_count, store.allowedPackages().size(), AllowedAppPolicy.MAX_APPS)); }
}
