package au.caravan.controller;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Home-screen widget: battery, solar, fridge temps and alarm status. Refreshed by BleService. */
public class CaravanWidget extends AppWidgetProvider {
    private static final int OK = 0xFF4ADE80, WARN = 0xFFFBBF24, BAD = 0xFFF87171, STALE = 0xFF5B6773, TXT = 0xFFF2F5F8;

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        refresh(context);
    }

    static void refresh(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int[] ids = m.getAppWidgetIds(new ComponentName(c, CaravanWidget.class));
        if (ids == null || ids.length == 0) return;

        boolean fresh = CaravanState.fresh();
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget);

        float soc = CaravanState.soc;
        int socCol = (!fresh || Float.isNaN(soc)) ? STALE : soc < 50 ? BAD : soc < 55 ? WARN : OK;
        v.setTextViewText(R.id.w_soc, CaravanState.pct(soc));
        v.setTextColor(R.id.w_soc, socCol);

        v.setTextViewText(R.id.w_ttg, CaravanState.timeLeft());
        v.setTextColor(R.id.w_ttg, fresh ? TXT : STALE);
        v.setTextViewText(R.id.w_solar, "Solar " + CaravanState.watts(CaravanState.solarW));
        v.setTextColor(R.id.w_solar, fresh ? TXT : STALE);

        float d = CaravanState.dom, w = CaravanState.wae;
        v.setTextViewText(R.id.w_fridge, "Dometic " + CaravanState.temp(d) + "   Waeco " + CaravanState.temp(w));
        boolean warm = (!Float.isNaN(d) && d > 8) || (!Float.isNaN(w) && w > 8);
        v.setTextColor(R.id.w_fridge, !fresh ? STALE : warm ? WARN : TXT);

        String status;
        int statusCol;
        int top = CaravanState.topAlarm();
        if (!fresh) {
            long t = CaravanState.lastRx;
            status = t == 0 ? "No signal" : "No signal · last seen " + new SimpleDateFormat("h:mm a", Locale.getDefault()).format(new Date(t));
            statusCol = STALE;
        } else if (top >= 0) {
            status = CaravanState.ALARMS[top];
            statusCol = BAD;
        } else if ((CaravanState.latched & 0xF) != 0) {
            status = "SILENCED";
            statusCol = WARN;
        } else if (CaravanState.greyFull()) {
            status = "Grey water FULL";
            statusCol = WARN;
        } else {
            status = "ALL OK";
            statusCol = OK;
        }
        v.setTextViewText(R.id.w_status, status);
        v.setTextColor(R.id.w_status, statusCol);

        Intent open = new Intent(c, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        v.setOnClickPendingIntent(R.id.w_root,
                PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));

        for (int id : ids) m.updateAppWidget(id, v);
    }
}
