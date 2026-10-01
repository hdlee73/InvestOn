#!/usr/bin/env bash
set -euo pipefail
mkdir -p verification
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -c
adb shell am start -W -n com.hdlee.investon/.MainActivity
sleep 12
adb logcat -d > verification/android-logcat.txt
adb shell pidof com.hdlee.investon
if grep -E 'FATAL EXCEPTION|Cannot schedule background price checks' verification/android-logcat.txt; then exit 1; fi
adb shell uiautomator dump /sdcard/investon.xml
adb pull /sdcard/investon.xml verification/android-window.xml
python3 - <<'CHECK'
import xml.etree.ElementTree as ET
nodes=ET.parse('verification/android-window.xml').getroot().iter('node')
text=' '.join(n.get('text','')+' '+n.get('content-desc','') for n in nodes)
assert '관심종목' in text and '포트폴리오' in text and '코스피' in text, text
CHECK
adb shell screencap -p /sdcard/investon.png
adb pull /sdcard/investon.png verification/android-launch.png
# Check that a persisted price-check job was successfully scheduled.
adb shell dumpsys jobscheduler > verification/android-jobs.txt
grep -F 'com.hdlee.investon/.AlertJob' verification/android-jobs.txt
# Restarting must also open successfully with saved state.
adb shell am force-stop com.hdlee.investon
adb shell am start -W -n com.hdlee.investon/.MainActivity
sleep 3
adb shell pidof com.hdlee.investon
adb logcat -d > verification/android-logcat.txt
if grep -E 'FATAL EXCEPTION|Cannot schedule background price checks' verification/android-logcat.txt; then exit 1; fi

# Native XLSX round trip, Excel shared strings/order edits, and live ETF catalog search.
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.hdlee.investon.test/com.hdlee.investon.BackupWorkbookTest | tee verification/android-instrumentation.txt
grep -F 'OK (5 tests)' verification/android-instrumentation.txt
adb pull /sdcard/Android/data/com.hdlee.investon/files/native-backup-test.xlsx verification/android-backup-test.xlsx
