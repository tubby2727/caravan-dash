package au.caravan.controller;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import androidx.core.app.ActivityCompat;

import com.getcapacitor.BridgeActivity;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends BridgeActivity {
    private static final int REQ = 77;
    private boolean asked;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(CaravanPlugin.class);
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onResume() {
        super.onResume();
        ensureRunning();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ) ensureRunning();
    }

    private void ensureRunning() {
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            add(need, Manifest.permission.BLUETOOTH_SCAN);
            add(need, Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            add(need, Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33) add(need, Manifest.permission.POST_NOTIFICATIONS);

        if (!need.isEmpty() && !asked) {
            asked = true;
            ActivityCompat.requestPermissions(this, need.toArray(new String[0]), REQ);
            return;
        }
        // start (or keep) the service once Bluetooth is allowed; notifications are optional
        boolean btOk = Build.VERSION.SDK_INT >= 31
                ? has(Manifest.permission.BLUETOOTH_SCAN) && has(Manifest.permission.BLUETOOTH_CONNECT)
                : has(Manifest.permission.ACCESS_FINE_LOCATION);
        if (btOk) BleService.start(this);
    }

    private void add(List<String> l, String p) {
        if (!has(p)) l.add(p);
    }

    private boolean has(String p) {
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }
}
