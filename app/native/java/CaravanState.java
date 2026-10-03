package au.caravan.controller;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;

/** Latest values from the controller, shared by the service, the widget and the plugin. */
final class CaravanState {
    static final String[] ALARMS = {"BATTERY CRITICAL", "FRIDGE WARM", "BATTERY LOW", "GREY WATER"};

    static volatile float soc = Float.NaN, volts = Float.NaN, amps = Float.NaN, ah = Float.NaN, ttg = Float.NaN;
    static volatile float solarW = Float.NaN, solarA = Float.NaN, yieldKwh = Float.NaN;
    static volatile float wae = Float.NaN, dom = Float.NaN;
    static volatile int mppt = 255, flags = 0, latched = 0, silenced = 0, shown = -1;
    static volatile long lastRx = 0;
    static volatile boolean connected = false;
    static volatile String conn = "searching";

    private static final byte[][] raw = new byte[4][];

    private CaravanState() {}

    static synchronized void store(int idx, byte[] v) {
        if (idx >= 0 && idx < 4) raw[idx] = v;
    }

    static synchronized byte[] get(int idx) {
        return (idx >= 0 && idx < 4) ? raw[idx] : null;
    }

    /** idx: 0 level (not decoded here), 1 battery, 2 solar, 3 status. Same layout as caravan_ble.yaml. */
    static void decode(int idx, byte[] v) {
        ByteBuffer b = ByteBuffer.wrap(v).order(ByteOrder.LITTLE_ENDIAN);
        if (idx == 1 && v.length >= 10) {
            soc = s16(b, 0, 10f);
            volts = s16(b, 2, 100f);
            amps = s16(b, 4, 100f);
            ah = s16(b, 6, 10f);
            ttg = s16(b, 8, 1f);
        } else if (idx == 2 && v.length >= 9) {
            solarW = s16(b, 0, 1f);
            solarA = s16(b, 2, 100f);
            yieldKwh = b.getFloat(4);
            mppt = v[8] & 0xFF;
        } else if (idx == 3 && v.length >= 8) {
            wae = s16(b, 0, 10f);
            dom = s16(b, 2, 10f);
            flags = v[4] & 0xFF;
            latched = v[5] & 0xFF;
            silenced = v[6] & 0xFF;
            shown = v[7];
        } else {
            return;
        }
        lastRx = System.currentTimeMillis();
    }

    private static float s16(ByteBuffer b, int o, float k) {
        short n = b.getShort(o);
        return n == 0x7FFF ? Float.NaN : n / k;
    }

    static boolean fresh() {
        return connected && System.currentTimeMillis() - lastRx < 10000;
    }

    static boolean greyFull() {
        return (flags & 1) != 0;
    }

    /** Lowest-numbered alarm that is latched and not silenced, or -1. */
    static int topAlarm() {
        int s = latched & ~silenced & 0xF;
        for (int i = 0; i < 4; i++) {
            if (((s >> i) & 1) != 0) return i;
        }
        return -1;
    }

    static String pct(float v) {
        return Float.isNaN(v) ? "--" : Math.round(v) + "%";
    }

    static String temp(float v) {
        return Float.isNaN(v) ? "--" : String.format(Locale.US, "%.1f°", v);
    }

    static String watts(float v) {
        return Float.isNaN(v) ? "--" : Math.round(v) + " W";
    }

    static String amp(float v) {
        return Float.isNaN(v) ? "--" : String.format(Locale.US, "%+.1f A", v);
    }

    /** "3h 20m left", "Charging", or "--". */
    static String timeLeft() {
        if (Float.isNaN(amps)) return "--";
        if (amps < -0.05f && !Float.isNaN(ttg) && ttg > 0) {
            int m = Math.round(ttg);
            if (m >= 1440) return (m / 1440) + "d " + ((m % 1440) / 60) + "h left";
            return (m / 60) + "h " + String.format(Locale.US, "%02d", m % 60) + "m left";
        }
        return amps > 0.05f ? "Charging" : "Idle";
    }

    static String alarmDetail(int a) {
        switch (a) {
            case 0:
            case 2:
                return "State of charge " + pct(soc);
            case 1:
                return "Dometic " + temp(dom) + "   Waeco " + temp(wae);
            default:
                return "Float switch closed";
        }
    }
}
