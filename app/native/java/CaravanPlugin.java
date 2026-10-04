package au.caravan.controller;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Base64;

import org.json.JSONObject;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/** Bridge between the web page and the background service. */
@CapacitorPlugin(name = "CaravanBle")
public class CaravanPlugin extends Plugin {

    @Override
    public void load() {
        BleService.listener = new BleService.Listener() {
            @Override
            public void onPacket(int idx, byte[] value) {
                JSObject o = new JSObject();
                o.put("i", idx);
                o.put("b", Base64.encodeToString(value, Base64.NO_WRAP));
                notifyListeners("packet", o);
            }

            @Override
            public void onConn(String state) {
                JSObject o = new JSObject();
                o.put("state", state);
                notifyListeners("conn", o);
            }
        };
    }

    @Override
    protected void handleOnDestroy() {
        BleService.listener = null;
    }

    /** Latest packet of each kind plus the connection state, for when the page was asleep. */
    @PluginMethod
    public void snapshot(PluginCall call) {
        JSObject r = new JSObject();
        for (int i = 0; i < 9; i++) {
            byte[] b = CaravanState.get(i);
            if (b != null) r.put("p" + i, Base64.encodeToString(b, Base64.NO_WRAP));
        }
        r.put("state", CaravanState.conn);
        com.getcapacitor.JSArray cf = new com.getcapacitor.JSArray();
        for (int v : CaravanState.cfg) cf.put(v == Integer.MIN_VALUE ? JSONObject.NULL : (Object) v);
        r.put("cfg", cf);
        call.resolve(r);
    }

    /** cmd 1 = silence 30 min, 2 = silence until cleared. */
    @PluginMethod
    public void command(PluginCall call) {
        int cmd = call.getInt("cmd", 0);
        BleService s = BleService.instance;
        JSObject r = new JSObject();
        r.put("ok", s != null && cmd > 0 && s.writeCmd(cmd));
        call.resolve(r);
    }

    /** Send raw bytes (base64) to the controller's command characteristic: settings, history request, clock. */
    @PluginMethod
    public void write(PluginCall call) {
        String b64 = call.getString("b");
        BleService s = BleService.instance;
        JSObject r = new JSObject();
        boolean ok = false;
        if (s != null && b64 != null) {
            try {
                ok = s.writeBytes(Base64.decode(b64, Base64.DEFAULT));
            } catch (Exception ignored) {
            }
        }
        r.put("ok", ok);
        call.resolve(r);
    }

    /** Save the controller PIN on this phone and send it now if connected. Empty string forgets it. */
    @PluginMethod
    public void setPin(PluginCall call) {
        String pin = call.getString("pin", "");
        if (pin == null) pin = "";
        pin = pin.trim();
        JSObject r = new JSObject();
        if (!pin.isEmpty() && !pin.matches("\\d{4,8}")) {
            r.put("ok", false);
            call.resolve(r);
            return;
        }
        BleService.savePin(getContext(), pin);
        BleService s = BleService.instance;
        if (s != null && CaravanState.connected && !pin.isEmpty()) s.sendPin();
        r.put("ok", true);
        call.resolve(r);
    }

    /** Whether a PIN is saved (the PIN itself is not handed back to the page). */
    @PluginMethod
    public void getPin(PluginCall call) {
        JSObject r = new JSObject();
        r.put("set", !BleService.getPin(getContext()).isEmpty());
        call.resolve(r);
    }

    /** Opens the system prompt that lets the app run in the background without being put to sleep. */
    @PluginMethod
    public void batterySettings(PluginCall call) {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getContext().getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
        } catch (Exception e) {
            try {
                Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getContext().getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(i);
            } catch (Exception ignored) {
            }
        }
        call.resolve();
    }

    /** Start the service again (for a Retry button). */
    @PluginMethod
    public void restart(PluginCall call) {
        BleService.start(getContext());
        call.resolve();
    }
}
