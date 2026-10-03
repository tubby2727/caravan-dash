package au.caravan.controller;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Foreground service: finds the caravan controller over BLE, keeps the connection,
 * decodes its packets, posts the live-status and alarm notifications and updates the widget.
 */
public class BleService extends Service {
    private static final String TAG = "CaravanBle";

    static final UUID SVC = UUID.fromString("c0a7a5e0-0001-4c1b-9f6e-5a1d0c4a0001");
    static final UUID[] CH = {
            UUID.fromString("c0a7a5e0-0002-4c1b-9f6e-5a1d0c4a0001"),   // level
            UUID.fromString("c0a7a5e0-0003-4c1b-9f6e-5a1d0c4a0001"),   // battery
            UUID.fromString("c0a7a5e0-0004-4c1b-9f6e-5a1d0c4a0001"),   // solar
            UUID.fromString("c0a7a5e0-0005-4c1b-9f6e-5a1d0c4a0001"),   // status
            UUID.fromString("c0a7a5e0-0007-4c1b-9f6e-5a1d0c4a0001"),   // settings
            UUID.fromString("c0a7a5e0-0008-4c1b-9f6e-5a1d0c4a0001"),   // history
    };
    static final UUID CMD = UUID.fromString("c0a7a5e0-0006-4c1b-9f6e-5a1d0c4a0001");
    static final UUID CCC = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    static final String CH_STATUS = "status";
    static final String CH_ALARM = "alarm";
    static final String CH_LINK = "link";
    static final int N_STATUS = 1, N_ALARM = 2, N_LINK = 3;

    /** Receives raw packets and connection changes (the WebView plugin). Called on the main thread. */
    interface Listener {
        void onPacket(int idx, byte[] value);

        void onConn(String state);
    }

    static volatile Listener listener;
    static volatile BleService instance;

    private final Handler h = new Handler(Looper.getMainLooper());
    private BluetoothAdapter adapter;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic cmdChar;
    private final ArrayDeque<byte[]> writeQ = new ArrayDeque<>();
    private boolean writing;
    private long lastClockSync;
    private final ArrayDeque<BluetoothGattCharacteristic> pending = new ArrayDeque<>();
    private boolean scanning;
    private int lastTop = -2;
    private boolean everConnected;
    private long lostSince;
    private boolean lostNotified;
    private String lastStatusKey = "";

    static void start(Context c) {
        try {
            ContextCompat.startForegroundService(c, new Intent(c, BleService.class));
        } catch (Exception e) {
            Log.w(TAG, "could not start service: " + e);
        }
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createChannels();
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = bm != null ? bm.getAdapter() : null;
        enterForeground();
        h.postDelayed(tick, 1000);
        startScan();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        enterForeground();   // required again for every startForegroundService call
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        h.removeCallbacksAndMessages(null);
        stopScan();
        if (gatt != null) {
            try {
                gatt.close();
            } catch (Exception ignored) {
            }
            gatt = null;
        }
        CaravanState.connected = false;
        instance = null;
        super.onDestroy();
    }

    private void enterForeground() {
        try {
            int type = Build.VERSION.SDK_INT >= 29 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE : 0;
            ServiceCompat.startForeground(this, N_STATUS, buildStatus(), type);
        } catch (Exception e) {
            Log.w(TAG, "startForeground failed: " + e);
            stopSelf();
        }
    }

    // ---------------------------------------------------------------- scanning and connecting

    private boolean hasBt() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void retryScanLater() {
        h.postDelayed(this::startScan, 5000);
    }

    @SuppressLint("MissingPermission")
    private void startScan() {
        if (gatt != null || scanning) return;
        if (!hasBt()) {
            setConn("noperm");
            retryScanLater();
            return;
        }
        if (adapter == null || !adapter.isEnabled()) {
            setConn("bt off");
            retryScanLater();
            return;
        }
        BluetoothLeScanner sc = adapter.getBluetoothLeScanner();
        if (sc == null) {
            retryScanLater();
            return;
        }
        List<ScanFilter> filters = new ArrayList<>();
        filters.add(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SVC)).build());
        filters.add(new ScanFilter.Builder().setDeviceName("caravan-controller").build());
        ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build();
        try {
            sc.startScan(filters, settings, scanCb);
            scanning = true;
            setConn("searching");
        } catch (Exception e) {
            Log.w(TAG, "scan failed: " + e);
            retryScanLater();
        }
    }

    @SuppressLint("MissingPermission")
    private void stopScan() {
        if (!scanning) return;
        scanning = false;
        try {
            BluetoothLeScanner sc = adapter != null ? adapter.getBluetoothLeScanner() : null;
            if (sc != null) sc.stopScan(scanCb);
        } catch (Exception ignored) {
        }
    }

    private final ScanCallback scanCb = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            h.post(() -> {
                if (gatt != null || !scanning) return;
                stopScan();
                connect(result.getDevice());
            });
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.w(TAG, "scan failed code " + errorCode);
            h.post(() -> {
                scanning = false;
                retryScanLater();
            });
        }
    };

    @SuppressLint("MissingPermission")
    private void connect(BluetoothDevice d) {
        setConn("connecting");
        try {
            gatt = d.connectGatt(this, false, gattCb, BluetoothDevice.TRANSPORT_LE);
        } catch (Exception e) {
            Log.w(TAG, "connectGatt failed: " + e);
            gatt = null;
            h.postDelayed(this::startScan, 2000);
        }
    }

    private final BluetoothGattCallback gattCb = new BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                h.post(() -> {
                    try {
                        g.discoverServices();
                    } catch (Exception e) {
                        Log.w(TAG, "discover failed: " + e);
                    }
                });
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                h.post(() -> onLost(g));
            }
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            h.post(() -> {
                BluetoothGattService svc = status == BluetoothGatt.GATT_SUCCESS ? g.getService(SVC) : null;
                if (svc == null) {
                    Log.w(TAG, "service not found, status " + status);
                    g.disconnect();
                    return;
                }
                cmdChar = svc.getCharacteristic(CMD);
                pending.clear();
                for (UUID u : CH) {
                    BluetoothGattCharacteristic c = svc.getCharacteristic(u);
                    if (c != null) pending.add(c);
                }
                enableNext();
            });
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            h.post(BleService.this::enableNext);
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            h.post(() -> {
                writing = false;
                writeNext();
            });
        }

        // Android 13+ delivers the value here
        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            if (value != null) {
                final byte[] copy = value.clone();
                final UUID u = c.getUuid();
                h.post(() -> onData(u, copy));
            }
        }

        // Older Android delivers it here
        @SuppressWarnings("deprecation")
        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            byte[] value = c.getValue();
            if (value != null) {
                final byte[] copy = value.clone();
                final UUID u = c.getUuid();
                h.post(() -> onData(u, copy));
            }
        }
    };

    @SuppressLint("MissingPermission")
    @SuppressWarnings("deprecation")
    private void enableNext() {
        if (gatt == null) return;
        BluetoothGattCharacteristic c = pending.poll();
        if (c == null) {
            markConnected();
            return;
        }
        try {
            gatt.setCharacteristicNotification(c, true);
            BluetoothGattDescriptor d = c.getDescriptor(CCC);
            if (d == null) {
                enableNext();
                return;
            }
            d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            if (!gatt.writeDescriptor(d)) h.postDelayed(this::enableNext, 300);
        } catch (Exception e) {
            Log.w(TAG, "enable notify failed: " + e);
            h.postDelayed(this::enableNext, 300);
        }
    }

    private void markConnected() {
        CaravanState.connected = true;
        everConnected = true;
        lostNotified = false;
        lostSince = 0;
        writeQ.clear();
        writing = false;
        sendClock();                      // lets the controller's quiet hours know the time of day
        CaravanState.lastRx = System.currentTimeMillis();
        getSystemService(NotificationManager.class).cancel(N_LINK);
        setConn("connected");
        updateOutputs();
    }

    @SuppressLint("MissingPermission")
    private void onLost(BluetoothGatt g) {
        try {
            g.close();
        } catch (Exception ignored) {
        }
        if (gatt != null && gatt != g) return;   // a stale callback from an older connection
        gatt = null;
        cmdChar = null;
        pending.clear();
        writeQ.clear();
        writing = false;
        if (CaravanState.connected) lostSince = System.currentTimeMillis();
        CaravanState.connected = false;
        setConn("searching");
        h.postDelayed(this::startScan, 1500);
        updateOutputs();
    }

    private void setConn(String s) {
        CaravanState.conn = s;
        Listener l = listener;
        if (l != null) l.onConn(s);
    }

    // ---------------------------------------------------------------- data

    private void onData(UUID u, byte[] v) {
        int idx = -1;
        for (int i = 0; i < CH.length; i++) {
            if (CH[i].equals(u)) idx = i;
        }
        if (idx < 0) return;
        CaravanState.store(idx, v);
        CaravanState.decode(idx, v);
        Listener l = listener;
        if (l != null) l.onPacket(idx, v);
        if (idx == 3) evaluateAlarm();
        // keep the controller's clock right (it has no clock of its own)
        if (CaravanState.connected && System.currentTimeMillis() - lastClockSync > 30 * 60 * 1000L) sendClock();
    }

    /** Send 1 (silence 30 min) or 2 (silence until cleared) to the controller. */
    boolean writeCmd(int cmd) {
        return writeBytes(new byte[]{(byte) cmd});
    }

    /** Queue any command for the controller's CMD characteristic. Writes go out one at a time. */
    boolean writeBytes(byte[] b) {
        if (gatt == null || cmdChar == null || b == null || b.length == 0 || b.length > 20) return false;
        final byte[] copy = b.clone();
        h.post(() -> {
            if (writeQ.size() > 40) writeQ.poll();
            writeQ.add(copy);
            writeNext();
        });
        return true;
    }

    @SuppressLint("MissingPermission")
    @SuppressWarnings("deprecation")
    private void writeNext() {
        if (writing || gatt == null || cmdChar == null) return;
        byte[] b = writeQ.poll();
        if (b == null) return;
        try {
            cmdChar.setValue(b);
            cmdChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            if (gatt.writeCharacteristic(cmdChar)) {
                writing = true;
                final BluetoothGatt gg = gatt;
                // if the completion callback never comes, do not block the queue for ever
                h.postDelayed(() -> {
                    if (writing && gatt == gg) {
                        writing = false;
                        writeNext();
                    }
                }, 1500);
            } else {
                h.postDelayed(this::writeNext, 100);
                writeQ.addFirst(b);
            }
        } catch (Exception e) {
            Log.w(TAG, "command write failed: " + e);
        }
    }

    /** Tell the controller the phone's time of day (opcode 0x13: minutes, seconds). */
    private void sendClock() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        int min = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE);
        writeBytes(new byte[]{0x13, (byte) (min & 0xFF), (byte) (min >> 8), (byte) c.get(java.util.Calendar.SECOND)});
        lastClockSync = System.currentTimeMillis();
    }

    // ---------------------------------------------------------------- notifications and widget

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateOutputs();
            h.postDelayed(this, 2000);
        }
    };

    private void updateOutputs() {
        // live-status notification: only re-post when the text changes
        String key = statusLine1() + "|" + statusLine2();
        if (!key.equals(lastStatusKey)) {
            lastStatusKey = key;
            try {
                getSystemService(NotificationManager.class).notify(N_STATUS, buildStatus());
            } catch (Exception ignored) {
            }
        }
        CaravanWidget.refresh(this);

        // lost contact for a while after having been connected
        if (!CaravanState.connected && everConnected && lostSince > 0 && !lostNotified
                && System.currentTimeMillis() - lostSince > 60000) {
            lostNotified = true;
            Notification n = new NotificationCompat.Builder(this, CH_LINK)
                    .setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle("Lost contact with the caravan")
                    .setContentText("Last reading: battery " + CaravanState.pct(CaravanState.soc)
                            + ", Dometic " + CaravanState.temp(CaravanState.dom))
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setAutoCancel(true)
                    .setContentIntent(openApp())
                    .build();
            getSystemService(NotificationManager.class).notify(N_LINK, n);
        }
    }

    private void evaluateAlarm() {
        int top = CaravanState.topAlarm();
        if (top == lastTop) return;
        lastTop = top;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (top < 0) {
            nm.cancel(N_ALARM);
        } else {
            nm.notify(N_ALARM, buildAlarm(top));
        }
    }

    private String statusLine1() {
        if (!CaravanState.connected) {
            return everConnected ? "Out of range" : "Looking for the controller…";
        }
        return "Battery " + CaravanState.pct(CaravanState.soc) + "  ·  Solar " + CaravanState.watts(CaravanState.solarW);
    }

    private String statusLine2() {
        String last = "Dometic " + CaravanState.temp(CaravanState.dom) + "   Waeco " + CaravanState.temp(CaravanState.wae)
                + "   Grey " + (CaravanState.greyFull() ? "FULL" : "OK");
        if (!CaravanState.connected) {
            return everConnected ? "Last: battery " + CaravanState.pct(CaravanState.soc) + ", " + last : "Tap to open";
        }
        return last;
    }

    private PendingIntent openApp() {
        Intent i = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent actionPi(int cmd) {
        Intent i = new Intent(this, ActionReceiver.class).setAction("au.caravan.controller.SILENCE").putExtra("cmd", cmd);
        return PendingIntent.getBroadcast(this, cmd, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification buildStatus() {
        return new NotificationCompat.Builder(this, CH_STATUS)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(statusLine1())
                .setContentText(statusLine2())
                .setStyle(new NotificationCompat.BigTextStyle().bigText(statusLine2()))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setContentIntent(openApp())
                .build();
    }

    private Notification buildAlarm(int top) {
        Notification n = new NotificationCompat.Builder(this, CH_ALARM)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(CaravanState.ALARMS[top])
                .setContentText(CaravanState.alarmDetail(top))
                .setStyle(new NotificationCompat.BigTextStyle().bigText(CaravanState.alarmDetail(top)))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setOnlyAlertOnce(false)
                .setColor(0xFFDC2626)
                .setContentIntent(openApp())
                .addAction(0, "Silence 30 min", actionPi(1))
                .addAction(0, "Until cleared", actionPi(2))
                .build();
        n.flags |= Notification.FLAG_INSISTENT;   // keep sounding until silenced or cleared
        return n;
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = getSystemService(NotificationManager.class);

        NotificationChannel st = new NotificationChannel(CH_STATUS, "Live status", NotificationManager.IMPORTANCE_LOW);
        st.setShowBadge(false);
        st.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(st);

        // Uses the notification volume, so it follows silent / Do Not Disturb like any other notification.
        NotificationChannel al = new NotificationChannel(CH_ALARM, "Caravan alarms", NotificationManager.IMPORTANCE_HIGH);
        al.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        al.enableVibration(true);
        al.setVibrationPattern(new long[]{0, 500, 300, 500, 300, 500});
        AudioAttributes aa = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        al.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), aa);
        al.setBypassDnd(false);
        nm.createNotificationChannel(al);

        NotificationChannel lk = new NotificationChannel(CH_LINK, "Connection lost", NotificationManager.IMPORTANCE_DEFAULT);
        lk.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(lk);
    }
}
