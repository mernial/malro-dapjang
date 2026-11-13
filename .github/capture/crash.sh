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
# 에뮬레이터는 컴퓨터 자판이 붙어 있어 화면 자판을 숨김 → "실물 자판이 있어도 화면 자판 보이기" 켬
adb shell settings put secure show_ime_with_hard_keyboard 1 || true
adb shell input keyevent KEYCODE_HOME; sleep 1
timeout 30 adb shell am start -n $PKG/.AskActivity; sleep 4
timeout 20 adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
B=$(timeout 20 adb shell cat /sdcard/ui.xml | tr '>' '\n' | grep 'class="android.widget.EditText"' | head -1 | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/')
echo "글 칸: $B"
if [ -n "$B" ]; then set -- $B; timeout 10 adb shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )); sleep 3; fi
timeout 30 adb exec-out screencap -p > shots/keyboard.png
# 이모티콘 판(위 탭): 😊 → 첫 탭 사진, '신앙' 탭(다섯째) 사진, '가'로 돌아감
# 같은 키 빨리 여러 번: ㅋ 를 다섯 번 (손 떼기 전 다음 누름 포함) → 글 칸에 'ㅋㅋㅋㅋㅋ' 가 다 들어가야 함
KX=217; KY=2028
for i in 1 2 3 4 5; do adb shell "input motionevent DOWN $KX $KY; input motionevent UP $KX $KY"; done
sleep 1
timeout 20 adb shell uiautomator dump /sdcard/t.xml >/dev/null 2>&1
echo "빨리 누르기 결과: $(timeout 20 adb shell cat /sdcard/t.xml | tr '>' '\n' | grep 'class="android.widget.EditText"' | head -1 | sed -E 's/.* text="([^"]*)".*/\1/')"
adb shell "input motionevent DOWN $KX $KY; input motionevent DOWN $KX $KY; input motionevent UP $KX $KY; input motionevent UP $KX $KY" || true
sleep 1
timeout 20 adb shell uiautomator dump /sdcard/t2.xml >/dev/null 2>&1
echo "겹쳐 누르기 결과: $(timeout 20 adb shell cat /sdcard/t2.xml | tr '>' '\n' | grep 'class="android.widget.EditText"' | head -1 | sed -E 's/.* text="([^"]*)".*/\1/')"
# 꾹 누르기: ㅋ 를 1.5초 누르고 있으면 여러 개
adb shell "input motionevent DOWN $KX $KY"; sleep 1.5; adb shell "input motionevent UP $KX $KY"
sleep 1
timeout 20 adb shell uiautomator dump /sdcard/t3.xml >/dev/null 2>&1
echo "꾹 누르기 결과: $(timeout 20 adb shell cat /sdcard/t3.xml | tr '>' '\n' | grep 'class="android.widget.EditText"' | head -1 | sed -E 's/.* text="([^"]*)".*/\1/')"
timeout 10 adb logcat -d -s talkhelper-kb | grep -i "key " | tail -20
for i in $(seq 1 30); do adb shell input keyevent KEYCODE_DEL; done
# 기호 자판: 아래 줄 자리가 한글과 같은지 (!#1 → 사진 → 가)
timeout 10 adb shell input tap 70 2182; sleep 2
timeout 30 adb exec-out screencap -p > shots/keyboard_sym.png
timeout 10 adb shell input tap 70 2182; sleep 2   # 두벌식은 같은 자리 '가' → 한글
timeout 10 adb shell input tap 295 2182; sleep 2
timeout 30 adb exec-out screencap -p > shots/keyboard_emoji.png
timeout 10 adb shell input tap 607 1674; sleep 2
timeout 30 adb exec-out screencap -p > shots/keyboard_emoji2.png
timeout 10 adb shell input tap 82 2182; sleep 2
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
if [ -n "$K" ]; then set -- $K; timeout 10 adb shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )); sleep 3
else timeout 10 adb shell input tap 950 1592; sleep 3; fi   # 키보드 창은 uiautomator 에 안 보일 때가 있어 자리로 누름 (위 줄 오른쪽 ⇄)
timeout 30 adb exec-out screencap -p > shots/keyboard_cji.png
# 천지인만: 같은 자리 키로 !#1 → 기호 → 123 큰 숫자판 → 가 (천지인으로)
timeout 10 adb shell input tap 78 2180; sleep 2
timeout 30 adb exec-out screencap -p > shots/keyboard_cji_sym.png
timeout 10 adb shell input tap 70 2182; sleep 2
timeout 30 adb exec-out screencap -p > shots/keyboard_num.png
timeout 10 adb shell input tap 60 2182; sleep 2
timeout 30 adb exec-out screencap -p > shots/keyboard_back.png
timeout 10 adb shell input tap 950 1592; sleep 2   # 다시 두벌식으로
timeout 20 adb shell cmd overlay enable com.android.internal.systemui.navbar.threebutton; sleep 3
# 폴드를 편 것처럼 넓은 화면으로 바꿔 키보드 사진 (자음·모음 사이 띄움 확인)
adb shell input keyevent KEYCODE_BACK; sleep 1
adb shell wm size 2200x1800; adb shell wm density 420; sleep 4
timeout 30 adb shell am start -n $PKG/.AskActivity; sleep 4
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1 || true   # 'Pixel Launcher 멈춤' 창 닫기
timeout 20 adb shell uiautomator dump /sdcard/w.xml >/dev/null 2>&1
W=$(timeout 20 adb shell cat /sdcard/w.xml | tr '>' '\n' | grep 'class="android.widget.EditText"' | head -1 | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/')
if [ -n "$W" ]; then set -- $W; timeout 10 adb shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )); sleep 3; fi
timeout 30 adb exec-out screencap -p > shots/keyboard_wide.png
timeout 10 adb shell input tap 1940 1074; sleep 3   # 위 줄 ⇄ → 천지인 (한쪽으로 모인 모양)
timeout 30 adb exec-out screencap -p > shots/keyboard_wide_cji.png
timeout 10 adb shell input tap 1940 1074; sleep 2   # 다시 두벌식
adb shell input keyevent KEYCODE_BACK; sleep 1; adb shell input keyevent KEYCODE_BACK; sleep 1
# 폴드를 가로로 든 것처럼 (낮고 넓은 화면) + 제스처 막대: 맨 아래 줄이 잘리지 않는지 (신부님 2026-11-12)
timeout 20 adb shell cmd overlay enable com.android.internal.systemui.navbar.gestural
adb shell wm size 2200x1650; adb shell wm density 420; sleep 4
timeout 30 adb shell am start -n $PKG/.AskActivity; sleep 4
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1 || true
timeout 20 adb shell uiautomator dump /sdcard/l.xml >/dev/null 2>&1
L=$(timeout 20 adb shell cat /sdcard/l.xml | tr '>' '\n' | grep 'class="android.widget.EditText"' | tail -1 | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/')
if [ -n "$L" ]; then set -- $L; timeout 10 adb shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )); sleep 3; fi
timeout 30 adb exec-out screencap -p > shots/keyboard_fold_land.png
timeout 10 adb logcat -d -s talkhelper-kb | tail -6
adb shell input keyevent KEYCODE_BACK; sleep 1; adb shell input keyevent KEYCODE_BACK; sleep 1
timeout 20 adb shell cmd overlay enable com.android.internal.systemui.navbar.threebutton
adb shell wm size reset; adb shell wm density reset; sleep 4
# 키보드까지 준비된 첫 화면 (새 디자인 확인): 위·아래로 한 장씩, 설정 화면 한 장
adb shell input keyevent KEYCODE_BACK; sleep 1; adb shell input keyevent KEYCODE_HOME; sleep 1
timeout 30 adb shell am start -n $PKG/.MainActivity; sleep 4
timeout 30 adb exec-out screencap -p > shots/main_ready.png
adb shell input swipe 540 1800 540 500 400; sleep 2
timeout 30 adb exec-out screencap -p > shots/main_ready2.png
# 키보드 색: '어두운색'을 눌러 미리 보기 사진, 다시 '베이지'로
adb shell input swipe 540 1600 540 900 400; sleep 2
timeout 20 adb shell uiautomator dump /sdcard/c.xml >/dev/null 2>&1
D=$(timeout 20 adb shell cat /sdcard/c.xml | tr '>' '\n' | grep 'text="어두운색"' | head -1 | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/')
echo "어두운색: $D"
if [ -n "$D" ]; then set -- $D; timeout 10 adb shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )); sleep 2; fi
timeout 30 adb exec-out screencap -p > shots/kb_color.png
B2=$(timeout 20 adb shell cat /sdcard/c.xml | tr '>' '\n' | grep 'text="✓ 베이지"' | head -1 | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/')
if [ -n "$B2" ]; then set -- $B2; timeout 10 adb shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )); sleep 1; fi
# '내 말투' 동의 창: 칩을 누르면 창이 뜨는지 → '동의하고 쓰기' → 다시 '정중하게'로
adb shell input swipe 540 700 540 1700 300; sleep 1
timeout 20 adb shell uiautomator dump /sdcard/m.xml >/dev/null 2>&1
M=$(timeout 20 adb shell cat /sdcard/m.xml | tr '>' '\n' | grep 'text="[^"]*내 말투"' | head -1 | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/')
echo "내 말투 칩: $M"
if [ -n "$M" ]; then set -- $M; timeout 10 adb shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )); sleep 2; fi
timeout 30 adb exec-out screencap -p > shots/mystyle_consent.png
timeout 20 adb shell uiautomator dump /sdcard/m2.xml >/dev/null 2>&1
Y=$(timeout 20 adb shell cat /sdcard/m2.xml | tr '>' '\n' | grep -i 'text="동의하고 쓰기"' | head -1 | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/')
echo "동의 단추: $Y"
if [ -n "$Y" ]; then set -- $Y; timeout 10 adb shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )); sleep 2; fi
timeout 30 adb exec-out screencap -p > shots/mystyle_after.png
timeout 20 adb shell uiautomator dump /sdcard/m3.xml >/dev/null 2>&1
P=$(timeout 20 adb shell cat /sdcard/m3.xml | tr '>' '\n' | grep 'text="[^"]*정중하게"' | head -1 | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/')
if [ -n "$P" ]; then set -- $P; timeout 10 adb shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )); sleep 1; fi
adb shell input swipe 540 500 540 1800 400; sleep 2
timeout 20 adb shell uiautomator dump /sdcard/m.xml >/dev/null 2>&1
G=$(timeout 20 adb shell cat /sdcard/m.xml | tr '>' '\n' | grep 'content-desc="설정"' | head -1 | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/')
echo "설정 단추: $G"
if [ -n "$G" ]; then set -- $G; timeout 10 adb shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )); sleep 3; timeout 30 adb exec-out screencap -p > shots/settings.png; fi
# 설정 맨 아래 '업데이트 소식'을 눌러 안내문 사진
adb shell input swipe 540 1800 540 400 400; sleep 2
timeout 20 adb shell uiautomator dump /sdcard/s.xml >/dev/null 2>&1
N=$(timeout 20 adb shell cat /sdcard/s.xml | tr '>' '\n' | grep 'text="업데이트 소식"' | head -1 | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/')
echo "업데이트 소식: $N"
if [ -n "$N" ]; then set -- $N; timeout 10 adb shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )); sleep 3; timeout 30 adb exec-out screencap -p > shots/news.png; fi
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
