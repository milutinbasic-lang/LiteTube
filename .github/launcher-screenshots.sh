#!/bin/bash
# Runs inside the emulator job: installs LiteLauncher as the home app and screenshots it.
set -x
PKG=io.github.milutinbasic_lang.litelauncher
mkdir -p shots
adb install -r launcher/app/build/outputs/apk/release/app-release.apk
adb shell cmd package set-home-activity "$PKG/app.litelauncher.LauncherActivity"
adb shell settings put global window_animation_scale 1
adb shell settings put global transition_animation_scale 1
adb logcat -c
adb shell input keyevent KEYCODE_HOME
sleep 8
read -r W H < <(adb shell wm size | tail -1 | sed 's/.*: //; s/x/ /')
adb exec-out screencap -p > shots/1-home.png

adb shell input swipe $((W / 2)) $((H * 3 / 4)) $((W / 2)) $((H / 4)) 250
sleep 4
adb exec-out screencap -p > shots/2-drawer.png

adb shell input keyevent KEYCODE_BACK
sleep 3
adb exec-out screencap -p > shots/3-back-home.png

# Open the drawer with the home bar, open Settings from it, then come back with Home.
adb shell input tap $((W / 2)) $((H - 8))
sleep 3
adb exec-out screencap -p > shots/4-drawer-via-bar.png
adb shell input keyevent KEYCODE_HOME
sleep 4
adb exec-out screencap -p > shots/5-home-again.png

# Long-press the wallpaper for the launcher menu.
adb shell input swipe $((W / 2)) $((H / 2)) $((W / 2)) $((H / 2)) 1200
sleep 2
adb exec-out screencap -p > shots/6-home-menu.png

# Turn on "Use Android status bar" from that menu.
adb shell uiautomator dump /sdcard/ui.xml > /dev/null
adb shell cat /sdcard/ui.xml > shots/ui-menu.xml
B=$(grep -o 'text="Use Android status bar"[^>]*bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' shots/ui-menu.xml \
  | grep -o 'bounds="[^"]*"' | grep -o '[0-9]\+' | tr '\n' ' ')
read -r X1 Y1 X2 Y2 <<< "$B"
if [ -n "$X1" ]; then
  adb shell input tap $(((X1 + X2) / 2)) $(((Y1 + Y2) / 2))
  sleep 3
  adb exec-out screencap -p > shots/7-android-status-bar.png
else
  echo "menu item not found" >> shots/errors.txt
  adb shell input keyevent KEYCODE_BACK
fi

adb shell dumpsys meminfo "$PKG" > shots/meminfo.txt
adb logcat -d > shots/logcat.txt
grep -E "FATAL|AndroidRuntime|litelauncher" shots/logcat.txt | head -50 > shots/errors.txt
true
