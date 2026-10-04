package au.caravan.controller;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Draws the home-screen widgets as a picture, in the same style as the controller's new Home page:
 * solar -> battery -> van, the watt figures and the CHARGING / ON BATTERY strip.
 * Large (4x2): status + fridges, flow row, watts, strip. Small (2x2): status, battery, watts, fridges, strip.
 * Pure android.graphics (no Context), so it can be previewed off the phone.
 */
final class WidgetRender {
    static final int OK = 0xFF4ADE80, WARN = 0xFFFBBF24, BAD = 0xFFF87171, STALE = 0xFF5B6773, TXT = 0xFFF2F5F8,
            SEC = 0xFF8A96A3, TILE = 0xF0151B23, LINE = 0xFF232C37, TRACK = 0xFF2A3441, INNER = 0xFF0F141B,
            BLUE = 0xFF7CC4FF, ORANGE = 0xFFF2B36B, GREEN_FILL = 0xFF16804A, AMBER_FILL = 0xFF9A5B14,
            STRIP_OK = 0xFF12301F, STRIP_WARN = 0xFF33240F;

    private WidgetRender() {}

    /** Everything the widgets show, worked out once from CaravanState. */
    static final class Model {
        boolean fresh;
        String status = "No signal";
        int statusCol = STALE;
        float soc = Float.NaN, sol = Float.NaN, bat = Float.NaN, van = Float.NaN;
        boolean mains;
        int state;              // 0 no data, 1 charging, 2 on battery, 3 full
        String strip = "NO BATTERY DATA", stripShort = "No data";
        float wae = Float.NaN, dom = Float.NaN;
        int waeCol = STALE, domCol = STALE;

        String key() {
            return fresh + "|" + status + "|" + statusCol + "|" + r(soc) + "|" + r(sol) + "|" + r(bat) + "|" + r(van) + "|" + mains
                    + "|" + state + "|" + strip + "|" + r10(wae) + "|" + r10(dom) + "|" + waeCol + "|" + domCol;
        }

        private static String r(float v) { return Float.isNaN(v) ? "-" : String.valueOf(Math.round(v)); }
        private static String r10(float v) { return Float.isNaN(v) ? "-" : String.valueOf(Math.round(v * 10)); }
    }

    private static int lastState = 0;

    static Model model() {
        Model m = new Model();
        m.fresh = CaravanState.fresh();
        m.soc = CaravanState.soc;
        float v = CaravanState.volts, i = CaravanState.amps;
        boolean havePower = CaravanState.powerFresh();
        m.sol = havePower ? CaravanState.pfSol : (!Float.isNaN(CaravanState.solarA) && !Float.isNaN(v) ? Math.max(0, CaravanState.solarA * v) : Float.NaN);
        m.bat = havePower ? CaravanState.pfBat : (!Float.isNaN(i) && !Float.isNaN(v) ? i * v : Float.NaN);
        m.mains = havePower ? CaravanState.pfMains : (!Float.isNaN(m.sol) && !Float.isNaN(m.bat) && m.sol - m.bat < -15);
        m.van = havePower ? CaravanState.pfVan : (!Float.isNaN(m.sol) && !Float.isNaN(m.bat) && !m.mains ? Math.max(0, m.sol - m.bat) : Float.NaN);

        // charging / on battery / full, same rules as the controller and the app
        int st = lastState;
        if (Float.isNaN(m.soc) || Float.isNaN(i)) st = 0;
        else if (m.soc >= 99.5f && i > -0.3f) st = 3;
        else if (i > 0.15f) st = 1;
        else if (i < -0.15f) st = 2;
        else if (st == 0 || st == 3) st = 2;
        lastState = st;
        m.state = st;

        float full = havePower ? CaravanState.pfFull
                : (st == 1 && !Float.isNaN(CaravanState.ah) && i > 0.3f ? Math.abs(CaravanState.ah) / i * 60f : Float.NaN);
        float ttg = CaravanState.ttg;
        if (st == 0) { m.strip = "NO BATTERY DATA"; m.stripShort = "No data"; }
        else if (st == 3) { m.strip = "BATTERY FULL"; m.stripShort = "Battery full"; }
        else if (st == 1) {
            if (m.mains) { m.strip = "CHARGING · on mains"; m.stripShort = "On mains"; }
            else if (!Float.isNaN(full)) { m.strip = "CHARGING · full in " + dur(full); m.stripShort = "Full in " + dur(full); }
            else { m.strip = "CHARGING"; m.stripShort = "Charging"; }
        } else {
            if (!Float.isNaN(ttg) && ttg > 0) {
                String d = ttg >= 1440 ? ((int) (ttg / 1440)) + " d " + ((int) (ttg % 1440 / 60)) + " h" : dur(ttg);
                m.strip = "ON BATTERY · " + d + " left"; m.stripShort = d + " left";
            } else { m.strip = "ON BATTERY"; m.stripShort = "On battery"; }
        }

        m.wae = CaravanState.wae; m.dom = CaravanState.dom;
        boolean fa = (CaravanState.latched & 2) != 0;
        m.waeCol = fridgeCol(m.wae, CaravanState.limit(2), fa);
        m.domCol = fridgeCol(m.dom, CaravanState.limit(3), fa);

        int top = CaravanState.topAlarm();
        if (!m.fresh) {
            long t = CaravanState.lastRx;
            m.status = t == 0 ? "No signal" : "No signal · " + new SimpleDateFormat("h:mm a", Locale.getDefault()).format(new Date(t));
            m.statusCol = STALE;
        } else if (top >= 0) { m.status = CaravanState.ALARMS[top]; m.statusCol = BAD; }
        else if ((CaravanState.latched & 0x3F) != 0) { m.status = "SILENCED"; m.statusCol = WARN; }
        else if (CaravanState.silenceAllMin > 0) { m.status = "ALL SILENCED"; m.statusCol = WARN; }
        else if (CaravanState.greyFull()) { m.status = "GREY WATER FULL"; m.statusCol = WARN; }
        else if (CaravanState.offMask != 0) {
            String[] n = {"CRIT BATT ALARM", "FRIDGE ALARM", "LOW BATT ALARM", "GREY ALARM", "SENSOR ALARM", "VICTRON ALARM", "BUZZER"};
            int cnt = Integer.bitCount(CaravanState.offMask & 0x7F), first = Integer.numberOfTrailingZeros(CaravanState.offMask);
            m.status = cnt == 1 && first < n.length ? n[first] + " OFF" : cnt + " ALARMS OFF";
            m.statusCol = WARN;
        }
        else { m.status = "ALL OK"; m.statusCol = OK; }
        return m;
    }

    private static int fridgeCol(float t, float lim, boolean alarm) {
        if (Float.isNaN(t)) return STALE;
        return t > lim ? (alarm ? BAD : WARN) : OK;
    }

    static String dur(float min) {
        int m = Math.round(min);
        return m < 60 ? m + " m" : (m / 60) + " h " + String.format(Locale.US, "%02d", m % 60) + " m";
    }

    // ------------------------------------------------------------------ drawing

    private static final class G {
        final Canvas c;
        final float k, ox, oy;          // design units -> pixels
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final boolean dim;

        G(Canvas c, float k, float ox, float oy, boolean dim) { this.c = c; this.k = k; this.ox = ox; this.oy = oy; this.dim = dim; }

        float x(float v) { return ox + v * k; }
        float y(float v) { return oy + v * k; }
        int col(int c) { return dim && c != TILE && c != LINE && c != INNER && c != TRACK ? (c & 0x00FFFFFF) | 0x80000000 : c; }

        void fill(int color) { p.setStyle(Paint.Style.FILL); p.setColor(col(color)); }
        void stroke(int color, float w) { p.setStyle(Paint.Style.STROKE); p.setColor(col(color)); p.setStrokeWidth(w * k); }

        void rrect(float l, float t, float w, float h, float r, int color) {
            fill(color);
            c.drawRoundRect(new RectF(x(l), y(t), x(l + w), y(t + h)), r * k, r * k, p);
        }

        void rrectStroke(float l, float t, float w, float h, float r, int color, float sw) {
            stroke(color, sw);
            c.drawRoundRect(new RectF(x(l), y(t), x(l + w), y(t + h)), r * k, r * k, p);
        }

        void circle(float cx, float cy, float r, int fillC, int strokeC) {
            fill(fillC); c.drawCircle(x(cx), y(cy), r * k, p);
            stroke(strokeC, 1f); c.drawCircle(x(cx), y(cy), r * k, p);
        }

        void line(float x1, float y1, float x2, float y2, int color, float w) {
            stroke(color, w);
            c.drawLine(x(x1), y(y1), x(x2), y(y2), p);
        }

        /** Text at a baseline, shrunk to fit maxW if needed. align: 0 left, 1 centre, 2 right. */
        void text(String s, float tx, float base, float size, boolean bold, int color, int align, float maxW) {
            fill(color);
            p.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            float sz = size;
            p.setTextSize(sz * k);
            while (maxW > 0 && p.measureText(s) > maxW * k && sz > 7) { sz -= 0.5f; p.setTextSize(sz * k); }
            p.setTextAlign(align == 0 ? Paint.Align.LEFT : align == 1 ? Paint.Align.CENTER : Paint.Align.RIGHT);
            c.drawText(s, x(tx), y(base), p);
        }

        /** Dotted flow line: round dots 3.2 wide, 7 apart. */
        void dots(float x1, float x2, float yy, int color, boolean on) {
            line(x1 - 2, yy, x2 + 2, yy, TRACK, 1.6f);
            if (!on) return;
            fill(color);
            for (float xx = x1; xx <= x2 + 0.1f; xx += 7f) c.drawCircle(x(xx), y(yy), 1.7f * k, p);
        }

        void sun(float cx, float cy, float r) {
            stroke(TXT, 1.4f);
            p.setStrokeCap(Paint.Cap.ROUND);
            c.drawCircle(x(cx), y(cy), r * 0.38f * k, p);
            for (int a = 0; a < 8; a++) {
                double t = a * Math.PI / 4;
                float c1 = r * 0.62f, c2 = r * 0.9f;
                c.drawLine(x(cx + (float) Math.cos(t) * c1), y(cy + (float) Math.sin(t) * c1),
                        x(cx + (float) Math.cos(t) * c2), y(cy + (float) Math.sin(t) * c2), p);
            }
        }

        void van(float cx, float cy, float r) {
            stroke(TXT, 1.4f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            float s = r / 9f;                      // icon drawn on an 18 x 18 grid centred on cx, cy
            float l = cx - 9 * s, t = cy - 9 * s;
            Path b = new Path();
            b.moveTo(x(l + 1.5f * s), y(t + 13 * s));
            b.lineTo(x(l + 1.5f * s), y(t + 6 * s));
            b.quadTo(x(l + 1.5f * s), y(t + 3.5f * s), x(l + 4 * s), y(t + 3.5f * s));
            b.lineTo(x(l + 13 * s), y(t + 3.5f * s));
            b.quadTo(x(l + 15.5f * s), y(t + 3.5f * s), x(l + 15.5f * s), y(t + 6 * s));
            b.lineTo(x(l + 15.5f * s), y(t + 13 * s));
            b.close();
            c.drawPath(b, p);
            c.drawLine(x(l + 15.5f * s), y(t + 11 * s), x(l + 17.5f * s), y(t + 11 * s), p);
            fill(INNER);
            c.drawCircle(x(l + 6 * s), y(t + 13.5f * s), 2.1f * s * k, p);
            stroke(TXT, 1.4f);
            c.drawCircle(x(l + 6 * s), y(t + 13.5f * s), 2.1f * s * k, p);
        }

        void bolt(float l, float t, float h) {
            float s = h / 31f;                     // the screen's bolt: 14 wide, 31 tall
            Path b = new Path();
            b.moveTo(x(l + 9 * s), y(t));
            b.lineTo(x(l), y(t + 17 * s));
            b.lineTo(x(l + 7 * s), y(t + 17 * s));
            b.lineTo(x(l + 3 * s), y(t + 31 * s));
            b.lineTo(x(l + 14 * s), y(t + 12 * s));
            b.lineTo(x(l + 7 * s), y(t + 12 * s));
            b.close();
            fill(0xFFFFFFFF);
            c.drawPath(b, p);
        }

        /** Battery outline with fill, bolt and %. */
        void battery(float l, float t, float w, float h, Model m, float pctSize) {
            rrect(l, t, w, h, 9, INNER);
            rrectStroke(l, t, w, h, 9, SEC, 2.2f);
            rrect(l + w, t + h * 0.3f, 5, h * 0.4f, 2, SEC);
            float inner = w - 8;
            if (!Float.isNaN(m.soc)) {
                float fw = inner * Math.max(0, Math.min(100, m.soc)) / 100f;
                if (fw > 1) rrect(l + 4, t + 4, fw, h - 8, 5, m.state == 2 ? AMBER_FILL : GREEN_FILL);
            }
            boolean chg = m.state == 1;
            if (chg) bolt(l + 10, t + 7, h - 14);
            String pct = Float.isNaN(m.soc) ? "--" : Math.round(m.soc) + "%";
            text(pct, l + w / 2 + (chg ? 7 : 0), t + h / 2 + pctSize * 0.36f, pctSize, true,
                    Float.isNaN(m.soc) ? STALE : 0xFFFFFFFF, 1, w - (chg ? 26 : 10));
        }
    }

    private static String w(float v) { return Float.isNaN(v) ? "--" : Math.round(v) + " W"; }

    private static String net(float v) {
        if (Float.isNaN(v)) return "--";
        int n = Math.round(v);
        return (n > 0 ? "+" : n < 0 ? "−" : "") + Math.abs(n) + " W";
    }

    private static int netCol(float v) {
        if (Float.isNaN(v)) return STALE;
        int n = Math.round(v);
        return n > 0 ? OK : n < 0 ? WARN : TXT;
    }

    private static String t1(float v) { return Float.isNaN(v) ? "--" : String.format(Locale.US, "%.1f°", v); }

    private static int stripBg(int st) { return st == 1 || st == 3 ? STRIP_OK : st == 2 ? STRIP_WARN : LINE; }
    private static int stripFg(int st) { return st == 1 || st == 3 ? OK : st == 2 ? WARN : SEC; }

    /** Draws a widget picture of wPx x hPx. small = the 2x2 version. */
    static Bitmap draw(Model m, int wPx, int hPx, float density, boolean small) {
        wPx = Math.max(40, wPx); hPx = Math.max(40, hPx);
        Bitmap bmp = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);

        // card background fills the whole widget
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        float rad = 20 * density, half = density * 0.5f;
        bg.setStyle(Paint.Style.FILL); bg.setColor(TILE);
        c.drawRoundRect(new RectF(half, half, wPx - half, hPx - half), rad, rad, bg);
        bg.setStyle(Paint.Style.STROKE); bg.setColor(LINE); bg.setStrokeWidth(density);
        c.drawRoundRect(new RectF(half, half, wPx - half, hPx - half), rad, rad, bg);

        // design box, scaled to fit and centred
        float dw = small ? 160 : 320, dh = 150;
        float k = Math.min(wPx / dw, hPx / dh);
        G g = new G(c, k, (wPx - dw * k) / 2f, (hPx - dh * k) / 2f, !m.fresh);
        if (small) drawSmall(g, m); else drawLarge(g, m);
        return bmp;
    }

    private static void drawLarge(G g, Model m) {
        // header: status left, fridges right
        g.text(m.status, 14, 19, 13.5f, true, m.statusCol, 0, 132);
        g.text("Wae " + t1(m.wae), 306, 19, 12.5f, true, m.waeCol == OK ? TXT : m.waeCol, 2, 70);
        g.text("Dom " + t1(m.dom), 228, 19, 12.5f, true, m.domCol == OK ? TXT : m.domCol, 2, 70);

        // flow row
        float cy = 52;
        g.dots(60, 102, cy, BLUE, !Float.isNaN(m.sol) && m.sol > 2);
        g.dots(222, 262, cy, ORANGE, !Float.isNaN(m.van) && m.van > 2 && !m.mains);
        g.circle(40, cy, 16, INNER, TRACK);
        g.sun(40, cy, 11);
        g.circle(282, cy, 16, INNER, TRACK);
        g.van(282, cy, 10);
        g.battery(110, cy - 23, 100, 46, m, 21);

        // watts and labels
        g.text(w(m.sol), 40, 95, 15, true, Float.isNaN(m.sol) ? STALE : TXT, 1, 70);
        g.text(net(m.bat), 162, 95, 15, true, netCol(m.bat), 1, 80);
        g.text(m.mains ? "Mains" : w(m.van), 282, 95, 15, true, m.mains || !Float.isNaN(m.van) ? TXT : STALE, 1, 70);
        g.text("Solar", 40, 108, 10.5f, false, SEC, 1, 70);
        g.text("Battery", 162, 108, 10.5f, false, SEC, 1, 80);
        g.text("Van using", 282, 108, 10.5f, false, SEC, 1, 70);

        // strip
        g.rrect(10, 116, 300, 26, 7, stripBg(m.state));
        g.text(m.strip, 160, 134, 14, true, stripFg(m.state), 1, 286);
    }

    private static void drawSmall(G g, Model m) {
        g.text(m.status, 80, 19, 12.5f, true, m.statusCol, 1, 140);
        g.battery(24, 28, 106, 46, m, 21);
        g.text(net(m.bat), 80, 96, 15, true, netCol(m.bat), 1, 140);
        g.text("Dom " + t1(m.dom), 43, 114, 11.5f, true, m.domCol == OK ? TXT : m.domCol, 1, 70);
        g.text("Wae " + t1(m.wae), 117, 114, 11.5f, true, m.waeCol == OK ? TXT : m.waeCol, 1, 70);
        g.rrect(8, 121, 144, 22, 6, stripBg(m.state));
        g.text(m.stripShort, 80, 136.5f, 12, true, stripFg(m.state), 1, 134);
    }
}
