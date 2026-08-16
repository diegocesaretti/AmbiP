package com.bwa3d.ambiprojector;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/** Best-effort Android TV/projector launcher used when Auto Start is enabled. */
public final class ProjectorBootReceiver extends BroadcastReceiver {
    private static final String PREFS = "ambi_projector_settings";
    private static final String KEY_AUTO_START = "projectorAutoStart";

    @Override public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(KEY_AUTO_START, false)) return;
        try {
            Intent launch = new Intent(context, NetworkProjectorActivity.class);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            launch.putExtra("ambip_boot_start", true);
            context.startActivity(launch);
        } catch (Throwable ignored) {
            // Some newer/OEM Android builds can block background activity launches at boot.
        }
    }
}
