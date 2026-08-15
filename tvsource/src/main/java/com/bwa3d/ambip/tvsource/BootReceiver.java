package com.bwa3d.ambip.tvsource;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Optional boot helper. On Oreo/Pie Android still allows this receiver to open the TV launcher,
 * which then shows the mandatory MediaProjection consent dialog. Newer Android versions restrict
 * background activity/MediaProjection startup, so we deliberately do not try to bypass them.
 */
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        SourceHub.load(context);
        if (!SourceHub.autoStartOnBoot) return;
        if (Build.VERSION.SDK_INT <= 28) {
            Intent open = new Intent(context, TvSourceActivity.class);
            open.putExtra(TvSourceActivity.EXTRA_BOOT_REQUEST, true);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            try { context.startActivity(open); } catch (Throwable ignored) {}
        }
    }
}
