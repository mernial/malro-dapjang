#!/bin/bash
# 에뮬레이터에서 카드 화면(HelperActivity)을 열어 보고, 앱이 죽으면 그 기록을 출력
APK=android/app/build/outputs/apk/play/release/app-play-release.apk
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
# 말로 답장 키보드: 켜고 골라서 글 칸을 눌러 키보드 사진
timeout 20 adb shell ime enable $PKG/.KeyboardService
timeout 20 adb shell ime set $PKG/.KeyboardService
adb shell input keyevent KEYCODE_HOME; sleep 1
timeout 30 adb shell am start -n $PKG/.AskActivity; sleep 4
timeout 20 adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
B=$(timeout 20 adb shell cat /sdcard/ui.xml | tr '>' '\n' | grep 'class="android.widget.EditText"' | head -1 | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/')
echo "글 칸: $B"
if [ -n "$B" ]; then set -- $B; timeout 10 adb shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )); sleep 3; fi
timeout 30 adb exec-out screencap -p > shots/keyboard.png
# 제스처 내비게이션(삼성 폰처럼 아래 막대만)에서도 아래 줄이 안 잘리는지
timeout 20 adb shell cmd overlay enable com.android.internal.systemui.navbar.gestural; sleep 4
adb shell input keyevent KEYCODE_BACK; sleep 1
if [ -n "$B" ]; then set -- $B; timeout 10 adb shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )); sleep 3; fi
timeout 30 adb exec-out screencap -p > shots/keyboard_gesture.png
timeout 10 adb logcat -d -s talkhelper-kb | tail -4
# 천지인으로 바꿔서도 아래 줄이 안 잘리는지 (위 줄의 '⇄ 천지인' 단추를 눌러 바꿈)
timeout 20 adb shell uiautomator dump /sdcard/kb.xml >/dev/null 2>&1
K=$(timeout 20 adb shell cat /sdcard/kb.xml | tr '>' '\n' | grep '천지인' | head -1 | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/')
echo "천지인 단추: $K"
if [ -n "$K" ]; then set -- $K; timeout 10 adb shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )); sleep 3; fi
timeout 30 adb exec-out screencap -p > shots/keyboard_cji.png
timeout 20 adb shell cmd overlay enable com.android.internal.systemui.navbar.threebutton; sleep 3
# 키보드까지 준비된 첫 화면 (새 디자인 확인): 위·아래로 한 장씩, 설정 화면 한 장
adb shell input keyevent KEYCODE_BACK; sleep 1; adb shell input keyevent KEYCODE_HOME; sleep 1
timeout 30 adb shell am start -n $PKG/.MainActivity; sleep 4
timeout 30 adb exec-out screencap -p > shots/main_ready.png
adb shell input swipe 540 1800 540 500 400; sleep 2
timeout 30 adb exec-out screencap -p > shots/main_ready2.png
adb shell input swipe 540 500 540 1800 400; sleep 2
timeout 20 adb shell uiautomator dump /sdcard/m.xml >/dev/null 2>&1
G=$(timeout 20 adb shell cat /sdcard/m.xml | tr '>' '\n' | grep 'content-desc="설정"' | head -1 | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/')
echo "설정 단추: $G"
if [ -n "$G" ]; then set -- $G; timeout 10 adb shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )); sleep 3; timeout 30 adb exec-out screencap -p > shots/settings.png; fi
adb shell input keyevent KEYCODE_BACK; sleep 1; adb shell input keyevent KEYCODE_BACK; sleep 1
# 중계 서버로 다듬기·찾기 한 번씩 (코드 숨김 뒤에도 되는지)
adb shell input keyevent KEYCODE_HOME; sleep 1
timeout 30 adb shell am start -n $PKG/.MainActivity --ez e2e true
for t in $(seq 1 30); do
  timeout 10 adb logcat -d -s talkhelper-e2e | grep -qE "OK|FAIL" && break; sleep 2
done
echo "== 서버 시험"
timeout 10 adb logcat -d -s talkhelper-e2e
echo "== 죽은 기록"
timeout 30 adb logcat -d -b crash
echo "== 앱 기록"
timeout 30 adb logcat -d | grep -iE "AndroidRuntime|talkhelper|FATAL" | head -150
