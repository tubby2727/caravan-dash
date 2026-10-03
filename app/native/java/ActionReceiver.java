package au.caravan.controller;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Handles the "Silence 30 min" / "Until cleared" buttons on the alarm notification. */
public class ActionReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        int cmd = intent.getIntExtra("cmd", 0);
        BleService s = BleService.instance;
        if (s != null && cmd > 0) s.writeCmd(cmd);
    }
}
