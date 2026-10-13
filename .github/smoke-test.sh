#!/usr/bin/env bash
# Installs the release APK on the emulator, opens a YouTube link and reports what happened
# as GitHub annotations (readable through the API, unlike raw logs).
set -u
APK=app/build/outputs/apk/release/app-release.apk
VIDEO="https://www.youtube.com/watch?v=jNQXAC9IVRw"
PKG=io.github.milutinbasic_lang.litetube

adb install -r "$APK"
adb logcat -c
adb shell am start -W -a android.intent.action.VIEW -d "$VIDEO" "$PKG"
sleep 50

screen() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb shell cat /sdcard/ui.xml > "$1" 2>/dev/null || true
}
screen ui.xml
adb shell dumpsys activity activities | grep -E "topResumedActivity|mResumedActivity" > activity.txt || true
adb shell dumpsys media_session | grep -E "state=PlaybackState|description=" | head -5 > media.txt || true

# --- Fullscreen exit: count orientation flips (bug report: endless flicker after back swipe) ---
sample() {  # $1 = label: record orientation every 0.5s for 8s
  for i in $(seq 1 16); do
    adb shell dumpsys window displays | grep -m1 -oE 'mCurrentRotation=[^ ]+|cur=[0-9]+x[0-9]+' | tr '\n' ' '
    echo
    sleep 0.5
  done > "orient_$1.txt"
}
tap_id() {  # tap the view with resource-id $1
  adb shell uiautomator dump /sdcard/ui2.xml >/dev/null 2>&1
  B=$(adb shell cat /sdcard/ui2.xml | grep -oE "resource-id=\"$1\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]" | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | head -1)
  if [ -z "$B" ]; then echo "no $1" ; return 1; fi
  read X1 Y1 X2 Y2 <<< "$(echo "$B" | sed 's/\]\[/,/; s/[][]//g' | tr ',' ' ')"
  adb shell input tap $(( (X1 + X2) / 2 )) $(( (Y1 + Y2) / 2 ))
}
back_swipe() {  # system back gesture: swipe in from the left edge
  W=$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1)
  H=${W#*x}; WW=${W%x*}
  adb shell input swipe 2 $((H / 2)) $((WW / 3)) $((H / 2)) 250
}
adb shell cmd overlay enable com.android.internal.systemui.navbar.gestural || true
sleep 3
# Scenario A: fullscreen button, then back gesture
# Controls may be hidden: if the fullscreen button isn't on screen, tap the video and retry.
if ! tap_id $PKG:id/exo_fullscreen > fs_tap.txt 2>&1; then
  adb shell input tap 160 90
  sleep 1
  tap_id $PKG:id/exo_fullscreen >> fs_tap.txt 2>&1 || echo "fullscreen button not found" >> fs_tap.txt
fi
sample A0
sleep 2
back_swipe
sample A
# Scenario B: rotate the device to landscape, then back gesture
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1
sleep 4
back_swipe
sample B
# Scenario C: same, but with the Back key (in case the emulator didn't treat the swipe as Back)
adb shell settings put system user_rotation 0
sleep 3
adb shell settings put system user_rotation 1
sleep 4
adb shell input keyevent KEYCODE_BACK
sample C
adb shell settings put system user_rotation 0
sleep 2

# Background playback: go Home, check the session is still playing.
adb shell input keyevent KEYCODE_HOME
sleep 5
adb shell dumpsys media_session | grep -E "state=PlaybackState" | head -3 > media_bg.txt || true

# Channel page
adb shell am start -W -a android.intent.action.VIEW -d "https://www.youtube.com/channel/UC4QobU6STFB0P71PMvOGN5A" "$PKG"
sleep 25
screen ui_channel.xml
adb logcat -d > logcat.txt

python3 .github/annotate.py
