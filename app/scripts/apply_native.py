#!/usr/bin/env python3
"""Copies the hand-written native code into the generated Capacitor Android project
and patches its manifest and signing config. Run after `npx cap add android`."""
import pathlib
import re
import shutil
import sys

APP = pathlib.Path(__file__).resolve().parent.parent
ANDROID = APP / "android"
if not ANDROID.exists():
    sys.exit("android/ not found: run `npx cap add android` first")

# --- Java sources and resources ---------------------------------------------------
pkg_dir = ANDROID / "app/src/main/java/au/caravan/controller"
pkg_dir.mkdir(parents=True, exist_ok=True)
for f in (APP / "native/java").glob("*.java"):
    shutil.copy(f, pkg_dir / f.name)
    print("copied", f.name)

shutil.copytree(APP / "native/res", ANDROID / "app/src/main/res", dirs_exist_ok=True)

# --- Manifest ---------------------------------------------------------------------
PERMS = """
    <uses-feature android:name="android.hardware.bluetooth_le" android:required="true" />
    <uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
    <uses-permission android:name="android.permission.BLUETOOTH_SCAN" android:usesPermissionFlags="neverForLocation" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
    <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
    <uses-permission android:name="android.permission.VIBRATE" />
"""

COMPONENTS = """
        <service
            android:name=".BleService"
            android:exported="false"
            android:foregroundServiceType="connectedDevice" />

        <receiver android:name=".ActionReceiver" android:exported="false" />

        <receiver android:name=".BootReceiver" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
                <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
            </intent-filter>
        </receiver>

        <receiver android:name=".CaravanWidget" android:exported="false">
            <intent-filter>
                <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
            </intent-filter>
            <meta-data
                android:name="android.appwidget.provider"
                android:resource="@xml/caravan_widget_info" />
        </receiver>
"""

mf = ANDROID / "app/src/main/AndroidManifest.xml"
s = mf.read_text()
if "BleService" not in s:
    assert "<application" in s and "</application>" in s, "unexpected manifest layout"
    s = s.replace("<application", PERMS + "\n    <application", 1)
    s = s.replace("</application>", COMPONENTS + "\n    </application>", 1)
    mf.write_text(s)
    print("patched manifest")

# --- Signing: use the committed debug keystore so every build can update the last one ---
gr = ANDROID / "app/build.gradle"
g = gr.read_text()
if "caravan-signing" not in g:
    block = """android {
    // caravan-signing: same key every build, so installs update in place
    signingConfigs {
        debug {
            storeFile file("../../debug.keystore")
            storePassword "android"
            keyAlias "androiddebugkey"
            keyPassword "android"
        }
    }"""
    g2, n = re.subn(r"^android\s*\{", block, g, count=1, flags=re.M)
    assert n == 1, "could not find android { block in build.gradle"
    gr.write_text(g2)
    print("patched build.gradle")
