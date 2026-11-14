# 말로 답장 키보드 — 찾기 쉬운 정리

신부님이 말씀하신 키보드 일을 "무엇을 · 어디서 · 왜" 로 정리한 것. 고칠 일이 생기면 여기서 먼저 찾을 것.
코드는 거의 모두 `android/app/src/main/java/kr/yunho/talkhelper/KeyboardService.kt` 한 파일에 있음.
줄 번호는 자꾸 바뀌므로 **함수 이름**으로 찾기 (파일 안 `// ─────────` 구역 표시 순서와 같음).

## 1. 지금 정해진 규칙 (바꾸기 전에 신부님께 여쭐 것)

| 규칙 | 정한 날 | 코드 |
|---|---|---|
| 글자 배열은 **삼성 키보드와 같게** (두벌식·천지인). 폴드에서도 자판을 나누지 않고 가운데 틈만 | 11-10, 11-13 | `qwerty()`, `cheonjiin()`, `mid()`, `MID_GAP` |
| **두벌식은 예전 그대로** (아래 줄에 ? ! ~ 넣는 안은 신부님이 거절) | 11-13 | `qwerty()` 아래 줄 |
| 기호 자판 = **삼성 기호 배열** (두벌식: 숫자 줄 + 기호 세 줄 / 천지인: 숫자 줄 대신 기호 한 줄 더). `1/2` = 둘째 장 | 11-13 | `symbols()`, `symPage` |
| **큰 숫자판은 천지인에서만**. 천지인 `!#1` → 기호 → `123` → 숫자판 → `가` → 천지인. 숫자만 받는 칸(전화번호 등)은 천지인이면 숫자판부터 | 11-13 | `numPad()`, `cjiNumPad()`, `toNumPad()`, `backToLetters()`, `onStartInputView()` |
| **아래 줄 칸 크기는 자판을 바꿔도 같게** — 두벌식 쪽(두벌식·영어·기호·이모티콘)은 `BOTTOM_W` 일곱 칸, 천지인 쪽(천지인·기호·숫자판)은 반칸·반칸·한칸·한칸·한칸 | 11-12, 11-13 | `BOTTOM_W`, `symbols()`, `numPad()`, `emojis()` |
| 기호 자판에서 **띄어쓰기를 누르면 글자 자판으로** (숫자판은 그대로) | 11-14 | `symbols()` 아래 줄 |
| 지우기(⌫)·띄어쓰기·줄바꿈 자리는 한글과 기호가 같은 자리 | 11-11 | `qwerty()`, `symbols()` |
| 줄바꿈 키 = 갈색 바탕 + 노란 큰 글씨 (어두운 키보드는 노란 바탕). 검색창이면 🔎 | 11-12, 11-14 | `enterKey()` |
| 말투 단추는 **그림만** (정중하게 = 합장 손 `ic_pray`) | 11-11 | `toneChips()`, `paintTone()`, `Ui.kt` |
| 다듬기·찾기 모델은 `claude-haiku-5-5` (Sonnet 안 씀) | — | `core/`, 서버 `server/` |
| 보내기는 **사용자가 직접** (카카오 정책). 키보드는 입력칸에 넣기만 | 11-10 | `put()` |

## 2. "이걸 고치려면 어디?" 찾아보기

### 자판 모양
- 키 하나의 모양·누름 처리 → `key()` (닿는 순간 입력, 꾹 누르면 계속, `alt` 꾹 누르면 다른 글자)
- 키 크기·간격 → `keyHdp`, `keyLp()`, `KEY_GAP_WIDE` / 폴드 키를 정사각형에 가깝게 → `squareWidthDp()`
- 폴드 가운데 틈 → `mid()`, `MID_GAP` (지금 2.6) / 띄어쓰기를 틈만큼 길게 → `spaceW()`
- 두벌식·영어 → `qwerty()` + `KO_ROWS`, `KO_SHIFT`, `EN_ROWS`
- 천지인 → `cheonjiin()` (같은 키 거듭 누르기 `cjiConsonant()`, 문장부호 `cyclePunct()`)
- 기호 → `symbols()` / 숫자판 → `numPad()` / 이모티콘 → `emojis()` + `EMOJI_GROUPS`, `FREQUENT`
- 자판 바꾸기 → `toKorean()`, `toEnglish()`, `toSymbols()`, `toNumPad()`, `backToLetters()`, `switchLayout()`
- 키보드 색 5가지 → `KbTheme.kt` (첫 화면 고르기·미리 보기 `MainActivity.kbPreview`)
- 맨 아래 줄이 잘릴 때 키 높이 줄이기 → `showKeys()` 안 `keyShrinkDp`
- 아래 내비게이션 막대 여백·색 → `onCreateInputView()` 아래쪽 (insets)

### 누름·입력
- 누른 키가 커지는 움직임 → `pop()` (커짐 1.18배, 돌아오는 시간 40ms)
- 키 사이 틈을 눌러도 가까운 키로 → `pressAt()`, `row()`
- 앞 키를 누른 채 다음 키 → `pressPointer()`
- 손을 다 떼지 않고 다시 누름(닿은 넓이로 판단) → `touchRepress()`, `touchStart()`
- 꾹 누르면 계속 입력(0.65초 뒤) → `key()` 안 `holdRep`
- 한글 조합 → `core/Hangul.kt` (천지인 겹받침·이모티콘 지우기 시험은 `core/HangulTest.kt`)
- 지우기가 이모티콘을 반쪽 남기지 않게 → `backspace()`, `lastGraphemeLength()`
- 띄어쓰기 두 번 = 마침표 → `space()`

### 블루투스·매직 키보드(실물 자판)
- 실물 자판 한글 조합 → `onKeyDown()` (키마다 설정을 조회하지 않음 — 느렸음), 한/영 → `toggleHwKorean()`
- 화면 자판을 숨길지 → `hwKeyboard()`, `onEvaluateInputViewShown()`

### 🎤 말로 답장 · 🔎 찾기
- 듣기 시작·신호음 → `startVoice()`, `onReady()`, `Sounds.kt`
- 구글 인식기 '삑' 막기 → `muteBeep()`, `unmuteBeep()` (스피커일 때만, 블루투스·이어폰은 안 끔 — 차 음량 0 문제)
- 이어 듣기·'듣지 못했어요' → `keepListening()`, `listener.onError()`, `resetRecognizer()`
- 다듬기 → `rewrite()` → 입력칸에 넣기 `put()` / 찾기 → `searchAll()`, `showAnswer()`
- '내 말투' 동의 → `askStyleConsent()` (키보드), `MainActivity.askStyleConsent()` (첫 화면), 예시 고르기 `ChatStore.myStyleAll()`, `core/MyStyle.kt`

### 말투·위 단축키 줄
- 말투 칩 → `toneChips()`, `switchTone()` / 단축키 순서 끌어 바꾸기 → `onCreateInputView()` 안 `tool()`, `toolOrder()`
- 안내 줄 → `showInfo()`, `hideInfo()` / 🚩 이상해요 → `askReport()`

## 3. 확인하는 법
- `claude/**` 브랜치에 올리면 `crash-check.yml` 이 에뮬레이터 사진을 `ui-shots` 브랜치 `shots/` 에 올림:
  `keyboard.png`(두벌식) · `keyboard_sym.png` · `keyboard_emoji.png` · `keyboard_cji.png` · `keyboard_cji_sym.png`(+`_sym2`) · `keyboard_num.png` · `keyboard_back.png` · `keyboard_fold_land.png` · `keyboard_fold_sym.png` · `kb_color.png` · `mystyle_consent.png`
- 같은 키 빨리 누르기·꾹 누르기 결과는 작업 기록에 `빨리 누르기 결과:` 줄로 남음 (`.github/capture/crash.sh`)
- 닿은 넓이·실물 자판·차 블루투스는 에뮬레이터로 시험 못 함 → 신부님 폰에서 확인
- 신부님이 "미리 보기"를 원하시면 합치지 말고 사진을 모아 보여 드린 뒤 "반영해" 하시면 합침

## 4. 올리기
- main 에 합치면 플레이 **내부 테스트**에 바로. 테스터(비공개 테스트)에는 **밤 11시 17분 하루 한 번** (`play-closed.yml`). 급하면 그 워크플로를 직접 실행
- 하루 저장 횟수 제한이 있으니 하루에 너무 여러 번 합치지 말 것 (11-12 'Daily save quota exceeded')
- 기능을 바꿀 때마다 `docs/policy-check.md` 표에 한 줄

## 5. 바뀐 기록 (버전 = 빌드 번호 + 100)
| 버전 | 날 | 내용 |
|---|---|---|
| 1.264~1.284 | 11-11 | 말투 그림만, 인식 개선, 키 커짐, 블루투스 키보드, 천지인 겹받침, 닿는 순간 입력, 차 블루투스 음량, 키 간격·폴드 정사각형, 이모티콘 지우기 |
| 1.286 | 11-11 | 기호 자판 ⌫ 자리 한글과 같게 |
| 1.288 | 11-11 | 이모티콘 탭(자주·표정·손·마음·신앙·자연·음식·사물) |
| 1.290 | 11-12 | 말해도 '듣지 못했어요' 뜨던 것 |
| 1.295 | 11-12 | 듣는 중 '삑' 없앰, 키보드 색 5가지, 폴드 아래 줄 잘림 |
| 1.299 | 11-12 | '내 말투' 동의·내 대화 예시, 키보드 색 미리 보기, 개인정보처리방침 |
| 1.301~1.308 | 11-12 | 폴드 틈·띄어쓰기, 아래 줄 기능 키·줄바꿈 키, 아래 줄 칸 통일 |
| 1.313 | 11-12 | 다 떼지 않고 다시 누름, 꾹 누르면 계속, 키 커짐 빨리 돌아옴 |
| 1.322 | 11-13 | 삼성식 기호 자판, 천지인 큰 숫자판, 아래 줄 통일, 실물 자판 반응 |
| 1.324 | 11-14 | 기호에서 띄어쓰기 → 글자 자판 |
| 1.326 | 11-14 | 폴드 기호 자판 가운데 틈 |
| 1.328 | 11-14 | 검색창 줄바꿈 키 돋보기 보이게 |

## 6. 아직 남은 것 (신부님 답을 기다림)
- 폴드 기호 자판을 삼성처럼 **네 줄**로 바꿀지 (지금은 휴대폰과 같은 다섯 줄)
- 기호 **둘째 장**을 삼성과 똑같이 — 삼성 키보드에서 1/2 누른 화면 사진이 있으면 맞춤
- 윗줄을 꾹 누르면 숫자(삼성식)로 할지 — 지금은 꾹 누르면 같은 글자가 계속
- 매직 키보드 반응이 실제로 빨라졌는지 폰에서 확인
