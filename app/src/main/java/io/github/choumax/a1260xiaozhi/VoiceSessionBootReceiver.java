package io.github.choumax.a1260xiaozhi;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import io.github.choumax.a1260xiaozhi.wake.WakeSettings;

/** Restores the user-selected wake service after boot; it never enables wake by itself. */
public final class VoiceSessionBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) { if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) && new WakeSettings(context).isEnabled()) context.startForegroundService(new Intent(context, VoiceSessionService.class).setAction(VoiceSessionService.ACTION_START)); }
}
