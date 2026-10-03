package au.caravan.controller;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the monitoring service after the phone boots or the app is updated. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        BleService.start(context);
    }
}
