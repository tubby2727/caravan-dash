package au.caravan.controller;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;

/** Latest values from the controller, shared by the service, the widget and the plugin. */
final class CaravanState {
    static final String[] ALARMS = {"BATTERY CRITICAL", "FRIDGE WARM", "BATTERY LOW", "GREY WATER", "SENSOR LOST"};

    static volatile float soc = Float.NaN, volts = Float.NaN, amps = Float.NaN, ah = Float.NaN, ttg = Float.NaN;
    static volatile float solarW = Float.NaN, solarA = Float.NaN, yieldKwh = Float.NaN;
    static volatile float wae = Float.NaN, dom = Float.NaN;
    static volatile int mppt = 255, flags = 0, latched = 0, silenced = 0, shown = -1;
    static volatile long lastRx = 0;
    static volatile boolean connected = false;
    static volatile String conn = "searching";

    private static final byte[][] raw = new byte[6][];
    /** Latest copy of every controller setting (index order as in caravan_ble.yaml), Integer.MIN_VALUE = not received. */
    static final int[] cfg = new int[20];
    static {
        java.util.Arrays.fill(cfg, Integer.MIN_VALUE);
    }

    private CaravanState() {}

    static synchronized void store(int idx, byte[] v) {
        if (idx >= 0 && idx < 4) raw[idx] = v;
        if (idx == 4 && v != null && v.length >= 3 && (v[0] & 0xFF) < cfg.length) {
            cfg[v[0] & 0xFF] = (short) ((v[1] & 0xFF) | (v[2] << 8));
        }
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
        int s = latched & ~silenced & 0x1F;
        for (int i = 0; i < 5; i++) {
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

    private static int cfgOr(int i, int def) {
        return cfg[i] == Integer.MIN_VALUE ? def : cfg[i];
    }

    private static String t1(float v) {
        return String.format(Locale.US, "%.1f\u00B0", v);
    }

    /** Sensors the controller is watching that are currently silent, e.g. "battery shunt, solar controller". */
    static String lostList() {
        int m = cfgOr(18, 11);
        StringBuilder sb = new StringBuilder();
        if ((m & 1) != 0 && Float.isNaN(soc)) sb.append("battery shunt, ");
        if ((m & 2) != 0 && Float.isNaN(solarW)) sb.append("solar controller, ");
        if ((m & 4) != 0 && Float.isNaN(wae)) sb.append("Waeco probe, ");
        if ((m & 8) != 0 && Float.isNaN(dom)) sb.append("Dometic probe, ");
        if (sb.length() == 0) return "";
        return sb.substring(0, sb.length() - 2);
    }

    /** One line saying what is wrong, for the notification. */
    static String alarmDetail(int a) {
        switch (a) {
            case 0:
                return "Battery " + pct(soc) + ", critical below " + cfgOr(0, 50) + "%";
            case 2:
                return "Battery " + pct(soc) + ", low below " + cfgOr(1, 55) + "%";
            case 1: {
                float tw = cfgOr(2, 80) / 10f, td = cfgOr(3, 80) / 10f;
                StringBuilder sb = new StringBuilder();
                if (!Float.isNaN(dom) && dom > td) sb.append("Dometic ").append(t1(dom)).append(" (limit ").append(t1(td)).append("), ");
                if (!Float.isNaN(wae) && wae > tw) sb.append("Waeco ").append(t1(wae)).append(" (limit ").append(t1(tw)).append("), ");
                if (sb.length() == 0) return "Fridge too warm. Dometic " + temp(dom) + "  Waeco " + temp(wae);
                return "Too warm: " + sb.substring(0, sb.length() - 2);
            }
            case 4: {
                String l = lostList();
                return l.isEmpty() ? "A sensor has stopped reporting" : "No data from: " + l;
            }
            default:
                return "Grey water tank is full, time to empty it";
        }
    }
}
