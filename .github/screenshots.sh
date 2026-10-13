#!/usr/bin/env bash
# Captures store screenshots on the emulator into ./shots
set -u
PKG=io.github.milutinbasic_lang.litetube
mkdir -p shots
shot() { sleep 1; adb exec-out screencap -p > "shots/$1.png"; }
tap_id() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  B=$(adb shell cat /sdcard/ui.xml | grep -oE "resource-id=\"$1\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]" | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | head -1)
  [ -z "$B" ] && { echo "no $1"; return 1; }
  read X1 Y1 X2 Y2 <<< "$(echo "$B" | tr -d '[]' | tr ',' ' ')"
  adb shell input tap $(( (X1 + X2) / 2 )) $(( (Y1 + Y2) / 2 ))
}
open_url() { adb shell am start -W -a android.intent.action.VIEW -d "$1" "$PKG" >/dev/null; }

adb install -r app/build/outputs/apk/release/app-release.apk
adb shell cmd uimode night no
# Clean status bar (demo mode): fixed clock, full battery and signal, no notifications.
adb shell settings put global sysui_demo_allowed 1
adb shell am broadcast -a com.android.systemui.demo -e command enter
adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 1200
adb shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false
adb shell am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4
adb shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false

# Subscribe to two channels; keep the second channel page as a screenshot.
open_url "https://www.youtube.com/@NASA"
sleep 15
tap_id "$PKG:id/subscribe"
open_url "https://www.youtube.com/@kurzgesagt"
sleep 15
tap_id "$PKG:id/subscribe"
sleep 2
shot 3_channel

# Home feed
adb shell am start -W -n "$PKG/app.litetube.ui.MainActivity" -f 0x04000000 >/dev/null
sleep 25
shot 1_feed

# Search
tap_id "$PKG:id/action_search"
sleep 2
adb shell input text "how%sbatteries%swork"
adb shell input keyevent KEYCODE_ENTER
sleep 15
shot 2_search

# Settings
adb shell input keyevent KEYCODE_BACK
sleep 2
tap_id "$PKG:id/action_settings" || { adb shell input keyevent KEYCODE_MENU; sleep 1; adb shell input tap 900 300; }
sleep 3
shot 5_settings

# Dark theme (the app follows the system by default)
adb shell input keyevent KEYCODE_BACK
adb shell cmd uimode night yes
sleep 4
adb shell am start -W -n "$PKG/app.litetube.ui.MainActivity" -f 0x04000000 >/dev/null
sleep 6
shot 4_dark

ls -la shots
