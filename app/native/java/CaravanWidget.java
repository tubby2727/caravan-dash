package au.caravan.controller;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.widget.RemoteViews;

import java.util.HashMap;
import java.util.Map;

/**
 * Home-screen widgets, drawn in the style of the controller's new Home page (see WidgetRender).
 * This class is the large 4x2 widget; CaravanWidgetSmall is the 2x2 one. Refreshed by BleService
 * every 2 s, but a widget is only redrawn when what it shows has changed.
 */
public class CaravanWidget extends AppWidgetProvider {
    private static final Map<Integer, String> lastKey = new HashMap<>();

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        lastKey.clear();
        refresh(context);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int id, Bundle newOptions) {
        lastKey.remove(id);                         // resized: draw again at the new size
        refresh(context);
    }

    @Override
    public void onDeleted(Context context, int[] ids) {
        for (int id : ids) lastKey.remove(id);
    }

    static synchronized void refresh(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int[] big = m.getAppWidgetIds(new ComponentName(c, CaravanWidget.class));
        int[] small = m.getAppWidgetIds(new ComponentName(c, CaravanWidgetSmall.class));
        if ((big == null || big.length == 0) && (small == null || small.length == 0)) return;
        WidgetRender.Model model = WidgetRender.model();
        String key = model.key();
        if (big != null) for (int id : big) draw(c, m, id, model, key, false);
        if (small != null) for (int id : small) draw(c, m, id, model, key, true);
    }

    private static void draw(Context c, AppWidgetManager m, int id, WidgetRender.Model model, String key, boolean small) {
        Bundle o = m.getAppWidgetOptions(id);
        // portrait size: min width x max height (dp)
        int wDp = o != null ? o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) : 0;
        int hDp = o != null ? o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) : 0;
        if (wDp <= 0) wDp = small ? 170 : 340;
        if (hDp <= 0) hDp = 160;
        String k = key + "|" + wDp + "x" + hDp;
        if (k.equals(lastKey.get(id))) return;
        lastKey.put(id, k);

        float density = Math.min(c.getResources().getDisplayMetrics().density, 2.75f);   // keeps the picture small
        Bitmap bmp = WidgetRender.draw(model, Math.round(wDp * density), Math.round(hDp * density), density, small);
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget);
        v.setImageViewBitmap(R.id.w_img, bmp);
        v.setContentDescription(R.id.w_img, model.status + ". Battery " + CaravanState.pct(model.soc) + ". " + model.strip);
        Intent open = new Intent(c, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        v.setOnClickPendingIntent(R.id.w_root,
                PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        m.updateAppWidget(id, v);
    }
}
