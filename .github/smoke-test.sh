#!/usr/bin/env bash
# Installs the release APK on the emulator, opens a YouTube link and reports what happened
# as GitHub annotations (readable through the API, unlike raw logs).
set -u
APK=app/build/outputs/apk/release/app-release.apk
VIDEO="https://www.youtube.com/watch?v=jNQXAC9IVRw"

adb install -r "$APK"
adb logcat -c
adb shell am start -W -a android.intent.action.VIEW -d "$VIDEO" app.litetube
sleep 50

adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
adb shell cat /sdcard/ui.xml > ui.xml 2>/dev/null || true
adb shell dumpsys activity activities | grep -E "topResumedActivity|mResumedActivity" > activity.txt || true
adb shell dumpsys media_session | grep -E "state=PlaybackState|description=" | head -5 > media.txt || true
adb logcat -d > logcat.txt

python3 .github/annotate.py
