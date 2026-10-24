package com.jisha.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the Home watcher (and Jisha itself) when the TV boots or Jisha is updated. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Intent svc = new Intent(context, HomeWatcherService.class);
        svc.putExtra(HomeWatcherService.EXTRA_BOOT, true);
        context.startService(svc);
    }
}
