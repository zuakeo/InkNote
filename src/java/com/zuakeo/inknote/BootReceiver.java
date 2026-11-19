package com.zuakeo.inknote;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            Intent i = new Intent(context, SyncService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(i);
            } else {
                context.startService(i);
            }
        } catch (Throwable ignored) { }
    }
}
