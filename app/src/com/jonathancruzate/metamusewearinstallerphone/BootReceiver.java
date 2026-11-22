package com.jonathancruzate.metamusewearinstallerphone;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Restarts the command lane after a reboot when Jonathan left it
 * switched on and the Worker is configured. (Background FGS start
 * from boot is permitted for BOOT_COMPLETED receivers.)
 */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        if (Store.getBool(ctx, Store.K_SERVICE_ON, false)
                && Store.workerConfigured(ctx)) {
            CommandService.start(ctx);
            AppLog.log(ctx, "SERVICE", "command lane restarted at boot");
        }
    }
}
