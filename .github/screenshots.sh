#!/usr/bin/env bash
# Captures store screenshots on the emulator into ./shots
set -u
PKG=io.github.milutinbasic_lang.litetube
MAIN="$PKG/app.litetube.ui.MainActivity"
mkdir -p shots
# Keep a trace next to the images (CI logs aren't always accessible).
exec > >(tee shots/log.txt) 2>&1
set -x

dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml; }
# Tap the centre of the first node matching attribute $1 (e.g. resource-id="x" or text="Wait").
tap_attr() {
  B=$(dump | grep -oE "$1[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]" | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | head -1)
  [ -z "$B" ] && return 1
  read X1 Y1 X2 Y2 <<< "$(echo "$B" | tr -d '[]' | tr ',' ' ')"
  adb shell input tap $(( (X1 + X2) / 2 )) $(( (Y1 + Y2) / 2 ))
}
tap_id() { tap_attr "resource-id=\"$1\""; }
# Close "isn't responding" dialogs from other apps if any appear.
dismiss() { tap_attr 'text="Wait"' && sleep 1; true; }
# Wait until a view with resource-id $1 is on screen (max $2 seconds).
wait_for() {
  for i in $(seq 1 "$2"); do
    dump | grep -q "resource-id=\"$1\"" && return 0
    dismiss; sleep 1
  done
  echo "timeout waiting for $1"; return 1
}
shot() { dismiss; sleep 2; adb exec-out screencap -p > "shots/$1.png"; echo "shot $1"; }
open_url() { adb shell am start -W -a android.intent.action.VIEW -d "$1" "$PKG" >/dev/null; }
home() { adb shell am start -W -n "$MAIN" -f 0x04000000 >/dev/null; }

subscribe() {
  open_url "$1"
  wait_for "$PKG:id/subscribe" 40 || return 1
  sleep 3
  for i in 1 2 3; do
    dump | grep -q 'text="UNSUBSCRIBE"' && return 0
    tap_id "$PKG:id/subscribe"; sleep 2
  done
  dump | grep -q 'text="UNSUBSCRIBE"' || echo "subscribe failed for $1"
}

adb install -r app/build/outputs/apk/release/app-release.apk
# The launcher isn't needed and tends to freeze on CI emulators ("isn't responding" dialogs).
adb shell pm disable-user --user 0 com.google.android.apps.nexuslauncher || true
adb shell cmd uimode night no
# Clean status bar (demo mode): fixed clock, full battery and signal, no notifications.
adb shell settings put global sysui_demo_allowed 1
adb shell am broadcast -a com.android.systemui.demo -e command enter >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 1200 >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4 >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false >/dev/null

subscribe "https://www.youtube.com/@NASA"
subscribe "https://www.youtube.com/@kurzgesagt"
shot 3_channel

# Home feed: wait for the refreshed list of videos.
home
wait_for "$PKG:id/thumb" 90
sleep 8
shot 1_feed

# Search
tap_id "$PKG:id/action_search"
wait_for "$PKG:id/query" 15
adb shell input text "how%sbatteries%swork"
adb shell input keyevent KEYCODE_ENTER
wait_for "$PKG:id/thumb" 40
sleep 8
shot 2_search

# Settings
home
wait_for "$PKG:id/action_settings" 15
tap_id "$PKG:id/action_settings"
sleep 4
shot 5_settings

# Dark theme (the app follows the system by default)
adb shell cmd uimode night yes
sleep 3
home
wait_for "$PKG:id/thumb" 30
sleep 6
shot 4_dark

ls -la shots
dump > shots/last_ui.xml
