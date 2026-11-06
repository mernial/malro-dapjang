#!/bin/bash
# 에뮬레이터에서 카드 화면(HelperActivity)을 열어 보고, 앱이 죽으면 그 기록을 출력
APK=android/app/build/outputs/apk/play/debug/app-play-debug.apk
PKG=kr.yunho.talkhelper
adb wait-for-device
adb root >/dev/null 2>&1; sleep 3; adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do sleep 3; done
adb install -r "$APK"
adb shell pm grant $PKG android.permission.RECORD_AUDIO || true
# 준비가 끝난 첫 화면을 찍으려고 시험용 설정을 넣음 (진짜 키 아님)
U=$(adb shell stat -c %u /data/data/$PKG | tr -d '\r')
adb shell "mkdir -p /data/data/$PKG/shared_prefs && echo \"<?xml version='1.0' encoding='utf-8' standalone='yes' ?><map><string name='apiKey'>test-not-a-key</string></map>\" > /data/data/$PKG/shared_prefs/settings.xml && chown -R $U:$U /data/data/$PKG/shared_prefs && restorecon -R /data/data/$PKG/shared_prefs"
adb shell settings put secure enabled_accessibility_services $PKG/$PKG.KakaoAccessibilityService
adb shell settings put secure accessibility_enabled 1
sleep 5
timeout 20 adb logcat -c
for i in 1 2 3; do
  echo "== 카드 열기 $i"
  timeout 30 adb shell am start -n $PKG/.HelperActivity
  sleep 6
  mkdir -p shots
  [ "$i" = 1 ] && timeout 30 adb exec-out screencap -p > shots/card.png
  adb shell input keyevent KEYCODE_BACK; sleep 2
done
mkdir -p shots
adb shell input keyevent KEYCODE_HOME; sleep 2
timeout 30 adb shell am start -n $PKG/.MainActivity; sleep 5
timeout 30 adb exec-out screencap -p > shots/main.png
adb shell input keyevent KEYCODE_BACK; sleep 1
# 간단 설정 창
timeout 30 adb shell am start -n $PKG/.QuickSettingsActivity; sleep 4
timeout 30 adb exec-out screencap -p > shots/quick.png
adb shell input keyevent KEYCODE_BACK; sleep 1
# 내보내기 안내 띠 (디버그판 확인용 신호)
adb shell input keyevent KEYCODE_HOME; sleep 2
timeout 30 adb shell am start -n $PKG/.HelperActivity --ez debugGuide true; sleep 4
timeout 30 adb exec-out screencap -p > shots/guide.png
echo "== 죽은 기록"
timeout 30 adb logcat -d -b crash
echo "== 앱 기록"
timeout 30 adb logcat -d | grep -iE "AndroidRuntime|talkhelper|FATAL" | head -150
