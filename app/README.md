# Caravan Android app

Capacitor wrapper around `../index.html` plus a native foreground service (BLE, alarms, widget).
Built by GitHub Actions (`.github/workflows/build-apk.yml`); download `app-debug.apk` from the Releases page.

- `native/java`  hand-written Java (BleService, CaravanState, CaravanPlugin, CaravanWidget, receivers, MainActivity)
- `native/res`   widget layout, icon, strings
- `scripts/apply_native.py`  copies the above into the generated `android/` project and patches the manifest and signing
- `debug.keystore`  fixed signing key so each new build installs over the last one
