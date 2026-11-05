#!/bin/bash
# 에뮬레이터에서 '말로 답장' 화면을 찍음. 실패해도 다음 단계로 계속.
APK=android/app/build/outputs/apk/play/debug/app-play-debug.apk
OUT=capture
mkdir -p "$OUT"
log() { echo "== $*" | tee -a "$OUT/log.txt"; }

dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1; }
# 글자를 찾아 누름 (최대 5번 다시 봄)
tap() {
  for i in 1 2 3 4 5; do
    dump
    if xy=$(python3 .github/capture/tap.py "$1" /tmp/ui.xml); then
      log "누름: $1 ($xy)"; adb shell input tap $xy; sleep 2; return 0
    fi
    sleep 1
  done
  log "못 찾음: $1"; return 1
}
shot() { sleep 1; adb exec-out screencap -p > "$OUT/$1.png"; log "찍음: $1"; }

adb wait-for-device
# 시스템 화면도 한국어로 (에뮬레이터는 root 가능)
adb root >/dev/null 2>&1; sleep 3; adb wait-for-device
adb shell "setprop persist.sys.locale ko-KR; setprop ctl.restart zygote"
sleep 5; adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do sleep 3; done
sleep 10
log "언어: $(adb shell getprop persist.sys.locale)"
adb shell settings put system screen_off_timeout 1800000
adb shell svc power stayon true
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard 2>/dev/null
log "설치"
adb install -r "$APK" | tee -a "$OUT/log.txt"
PKG=kr.yunho.talkhelper

# 1) 첫 화면 (준비 1/3: 접근성)
adb shell am start -n $PKG/.MainActivity; sleep 5
shot 01_첫화면

# 2) 접근성 안내·동의 흐름 녹화 (구글 심사 영상: 안내 전체 → 동의 안 함 → 다시 안내 → 동의 → 접근성 켜기)
adb shell screenrecord --time-limit 120 /sdcard/disclosure.mp4 &
REC=$!
sleep 2
tap "설정 열기"; sleep 1
shot 02_접근성안내
tap "동의 안 함"; sleep 2
shot 03_동의안함_후
tap "설정 열기"; sleep 2
tap "동의하고 켜기"; sleep 3
shot 04_접근성설정
# 시스템 접근성 화면: 앱 이름 → 사용 스위치 → 허용 (영어/한국어 둘 다 시도)
tap "말로 답장" || true
sleep 2; shot 05_접근성_앱화면
tap "말로 답장 사용" || tap "Use 말로 답장" || true
sleep 2; shot 06_허용묻기
tap "허용" || tap "Allow" || true
sleep 3; shot 07_접근성켜짐
adb shell input keyevent KEYCODE_BACK; sleep 1
adb shell input keyevent KEYCODE_BACK; sleep 1
adb shell am start -f 0x10008000 -n $PKG/.MainActivity; sleep 4
shot 08_다음단계
sleep 2
kill -INT $REC 2>/dev/null; adb shell pkill -INT screenrecord; sleep 4
adb pull /sdcard/disclosure.mp4 "$OUT/심사영상_동의흐름.mp4" && log "영상 받음"

# 3) 마이크 허용 뒤 다음 단계 화면
adb shell pm grant $PKG android.permission.RECORD_AUDIO
adb shell am start -f 0x10008000 -n $PKG/.MainActivity; sleep 4
shot 09_준비3_설정코드

# 4) 자세한 설정 (제목 길게 누르기)
dump
if xy=$(python3 .github/capture/tap.py "말로 답장" /tmp/ui.xml); then
  adb shell input swipe $xy $xy 1500; sleep 3; shot 10_자세한설정
fi
adb shell am start -n $PKG/.AskActivity; sleep 4
shot 11_대화에서찾기

adb shell dumpsys accessibility | grep -i talkhelper | head -5 | tee -a "$OUT/log.txt"
log "끝"
exit 0
