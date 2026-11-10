> **2026-11-10 바뀜:** 플레이판에서 접근성(카톡 위 버튼)·알림 접근을 뺐습니다. 아래 '접근성 API 신고서'는 더 이상 필요 없고(Play Console에서 접근성 사용 안 함으로), 소개 글의 '카톡 위 버튼' 부분은 키보드 방식으로 고쳐야 합니다.

# 구글 플레이 비공개 테스트 준비물과 순서

플레이 스토어판 이름은 **「말로 답장」**입니다. 다른 회사 상표인 '카톡'을 앱 이름과 아이콘에 쓰면 심사에서 거절될 수 있어서, 이름과 색을 바꿨습니다. 이름은 원하시는 대로 바꿀 수 있습니다. 바꾸려면 `android/app/src/play/res/values/strings.xml`을 수정합니다.

## 준비된 파일
| 무엇 | 어디 |
|---|---|
| 업로드할 앱 파일 (AAB) | https://github.com/mernial/malro-dapjang/releases/download/talk-helper/talk-helper-play.aab |
| 앱 아이콘 512×512 | `docs/play-store/icon-512.png` (2026-11-10 새 디자인: 크림·갈색 동그라미·노란 소리 막대, 앱 아이콘과 같음) |
| 그래픽 이미지 1024×500 | `docs/play-store/feature-1024x500.png` |
| 휴대폰 스크린샷 1080×1920 | `docs/play-store/screenshots/01~04.png` (말하면 입력칸에 · 말투 · 두벌식/천지인 · 두 단계 시작). 에뮬레이터 사진(`ui-shots`의 keyboard_gesture·keyboard_cji·main_ready·main)으로 `make-store.mjs`가 만듦 — 같은 폴더에 NanumSquareRound R/B/EB woff2 와 shots.json 을 두고 `node make-store.mjs <출력 폴더>` |
| 개인정보처리방침 | `talk-helper-privacy.html` (아래 '개인정보처리방침 주소' 참고) |

---

## 1. 개발자 계정 — 이미 있음
- 「가톨릭그램」(`com.catholicgram.android`)을 비공개 테스트 중인 **그 Play Console 계정**을 그대로 씁니다. 새로 가입하거나 25달러를 낼 필요가 없습니다.
- 개인 계정이라면 **이 앱도 따로** 테스터 12명 이상이 14일 동안 비공개 테스트를 해야 정식 출시를 신청할 수 있습니다.
- **가톨릭그램 테스터들을 이 앱의 테스터로도 그대로 등록**하면 사람을 새로 모을 필요가 없습니다. 같은 이메일 목록이나 Google 그룹을 고르면 됩니다.

## 2. 앱 만들기
- **앱 만들기** → 앱 이름 `말로 답장` · 기본 언어 `한국어` · **앱** · **무료** → 선언에 동의

## 3. 앱 설정 (대시보드의 "앱 설정" 항목들)
| 항목 | 답 |
|---|---|
| 개인정보처리방침 | 아래 '개인정보처리방침 주소' |
| 앱 액세스 권한 | **"모든 기능을 제한 없이 사용 가능"** (2026-11-07부터 중계 서버가 있어 키·설정 코드가 필요 없음) |
| 광고 | 광고 없음 |
| 콘텐츠 등급 | 설문: 카테고리 **유틸리티/생산성/커뮤니케이션 등**. 폭력·성·도박 모두 '아니요'. **사용자끼리 소통**: 아니요(앱이 자체 채팅을 제공하지 않음) |
| 타겟층 | **만 18세 이상** |
| 뉴스 앱 | 아니요 |
| 데이터 보안 | 아래 '데이터 보안 답변' |
| 정부 앱 | 아니요 |
| 금융 기능 | 없음 |
| 건강 | 없음 |
| **민감한 권한: 접근성 API** | **이제 해당 없음** (2026-11-10 플레이판에서 뺌) — Play Console에서 접근성 신고를 '사용 안 함'으로 |

## 4. 스토어 등록정보
(2026-11-10 새로 씀: 카카오 안내에 따라 카톡 위 버튼(접근성)을 빼고 **키보드 방식**으로 바뀜)
- **앱 이름 (30자):** `말로 답장 - 말로 쓰는 답장 키보드`
- **간단한 설명 (80자):** `말하면 내 말 그대로 예의 바르게 다듬어 입력칸에 넣어 주는 키보드. 자판이 어려운 분께.`
- **자세한 설명:**
```
하고 싶은 말을, 손 대신 말로.
「말로 답장」은 자판으로 글 쓰기가 어려운 분을 위한 '말로 쓰는 키보드'입니다.

키보드 위의 🎤 말로 답장을 누르고 편하게 말하세요.
"내일 못 간다고 해 줘"
→ "내일은 못 가요. 다음에 봐요."
다듬은 글이 입력칸에 들어갑니다. 확인하고 보내기만 누르세요.

■ 답장을 지어내지 않아요. 내 말을 다듬어요
• AI가 대신 그럴듯한 답을 만드는 앱이 아닙니다.
• 내가 말한 뜻 그대로, 예의 바른 문장으로 고쳐 줄 뿐입니다.
• 말하지 않은 약속·날짜·이유는 덧붙이지 않습니다.
• "신부님", "자매님", 세례명, "○○ 씨" 같은 호칭도 말한 그대로 지킵니다.

■ 평소처럼 쓰는 키보드
• 익숙한 두벌식과 천지인 자판을 고를 수 있어요.
• 영어·숫자·기호 자판도 있어요.
• 폴드처럼 큰 화면에서도 알맞게 보여요.

■ 키보드 위 단축키 줄
• 🎤 말로 답장: 말하면 다듬어 입력칸에 넣어 드려요.
• 말투: 정중하게 · 친구처럼 · 업무 연락 · 자녀에게 · 내 말투.
  방금 넣은 글도 누르면 그 말투로 바로 바뀌어요.
• 🔎 찾기: "지난번 모임 날짜 언제로 정했지?"처럼 말로 물으면 찾아 드려요.
  카카오톡의 '대화 내용 내보내기'로 이 앱에 보내 둔 대화에서 찾습니다.
• 자판 바꾸기: 두벌식 ↔ 천지인.

■ 보내기는 언제나 내가
• 앱이 대신 보내거나 다른 앱을 조작하지 않습니다.
• 다듬은 글을 입력칸에 넣을 뿐, 보내기 버튼은 직접 누르세요.

■ 이런 분께 좋아요
• 글씨가 작고 자판이 불편한 어르신, 부모님
• 존댓말·업무 말투가 늘 고민되는 분
• 바쁠 때 말로 빠르게 답장하고 싶은 분

■ 쉬운 시작
• 설치하고 두 가지만 하면 됩니다: 키보드 켜기 · 마이크 허용.
• 따로 가입하거나 키를 넣을 필요가 없습니다.

■ 개인정보
• 자판으로 친 글은 휴대폰 안에서만 쓰이고, 저장하거나 밖으로 보내지 않습니다.
• 🎤로 말한 내용(찾을 때는 질문과 관련된 대화 부분)만 중계 서버를 거쳐
  Anthropic의 Claude AI(미국)로 보내 다듬습니다. 중계 서버는 내용을 저장하지 않습니다.
• 음성은 휴대폰의 음성 인식이 글로 바꾸고, 녹음은 저장하지 않습니다.
• 이상한 AI 결과는 키보드의 🚩로 알려 주세요.

※ 이 앱은 카카오와 관련이 없는 독립 앱입니다.
```
- **카테고리:** 커뮤니케이션
- **연락처 이메일:** `mernial@naver.com`

## 5. 비공개 테스트 올리기
1. **테스트 및 출시 → 테스트 → 비공개 테스트** → **트랙 만들기**(또는 기본 'Closed testing')
2. **테스터**: 이메일 목록 만들기 → 테스터들의 **구글 계정(Gmail) 주소** 12개 이상 입력
3. **새 버전 만들기** → **Play 앱 서명**: "Google에서 앱 서명 키 관리"(권장) 그대로 → 위의 `talk-helper-play.aab` 업로드
   - 이 파일은 GitHub에 등록하신 서명 키로 서명돼 있고, 플레이에서는 이 키가 **업로드 키**가 됩니다.
4. 버전 이름은 자동으로 채워집니다. 출시 노트 예: `처음 비공개 테스트 버전입니다.`
5. **검토 시작** → 구글 심사(보통 며칠, 접근성 앱은 더 걸릴 수 있음)
6. 통과하면 테스터용 **참여 링크**가 생깁니다. 테스터는 링크 → **테스터 되기** → 플레이 스토어에서 **설치**합니다.
7. 12명 이상이 **14일 동안** 계속 설치해 둔 뒤 → **프로덕션(정식 출시) 신청**이 가능해집니다.

**주의:** 지금 직접 설치한 '카톡 도우미'(GitHub판)와 플레이판은 서명 키가 달라 같은 폰에 함께 깔 수 없습니다. 플레이판을 설치하기 전에 GitHub판을 지워 주세요.

---

## 개인정보처리방침 주소
이 저장소는 GitHub Pages로 공개되고 있어서, 작업 브랜치를 **main에 합치면** 아래 주소에서 열립니다.
`https://mernial.github.io/catholic-shorts/talk-helper-privacy.html`
합치기 전에는 그 주소가 열리지 않습니다. 정확한 주소는 저장소 Settings → Pages에서 확인하세요.

## 심사용 키
- 심사자가 앱을 써 볼 수 있어야 합니다. Claude Console에서 **심사용 API 키를 따로** 만드세요. 이름 예: `play-review`, 사용 한도를 낮게 걸어 두세요.
- 그 키로 앱의 **"가족에게 설정 보내기"** 코드를 만들어 '앱 액세스 권한' 안내에 붙여 넣습니다.
- 심사가 끝나면 그 키를 삭제하세요.

## 데이터 보안 답변
(2026-11-05 고침: 예전 답은 "저장 안 함·일시적 처리"였으나, 지금은 대화 내보내기를 폰에 저장하고 Anthropic이 API 데이터를 일정 기간 보관할 수 있어 아래처럼 답합니다.)

| 질문 | 답 |
|---|---|
| 데이터를 수집하거나 공유하나요? | **예** |
| 전송 중 암호화 | **예** (HTTPS) |
| 삭제 요청 방법 | 앱 안에서 직접 삭제(설정 → 저장한 대화·기록 지우기, 대화에서 찾기 → 이 대화 지우기), 앱 삭제 시 모두 삭제, 문의 메일 mernial@naver.com |
| **메시지 → 기타 앱 내 메시지** | **수집 예** · 공유 아니요 (Anthropic은 우리 대신 처리하는 서비스 제공자라 '공유'가 아님) · 일시적 처리 **아니요** · **필수** · 목적: **앱 기능** |
| 오디오 | 아니요. 음성은 휴대폰(구글) 음성 인식이 글로 바꾸고, 앱은 글만 보냅니다 |
| 사진 및 동영상 | 아니요. 내보낸 사진은 폰 안에만 두고 밖으로 보내지 않습니다 (기기 밖 전송이 없으면 '수집' 아님) |
| 개인 정보(이름) | 아니요. 설정의 '내 이름·나에 대해'는 메시지 다듬기 요청에 함께 들어가므로 위 '메시지'로 봅니다 |
| 키보드로 친 글 | 수집 아님. 한글 조합만 휴대폰 안에서 하고 저장·전송하지 않음 (🎤로 말한 내용만 위 '메시지') |
| 위치·연락처·기기 ID·앱 활동 등 | 모두 아니요 |

## 접근성 API 신고서 (2026-11-10부터 쓰지 않음 — 예전 기록)
Play Console → 앱 콘텐츠 → **민감한 권한 및 API** → 접근성 API.

- **isAccessibilityTool:** 사용 안 함. 장애인 보조 도구로 신청하지 않습니다.
- **앱 안 공개와 동의:** 있음. 첫 화면의 '설정 열기'를 누르면 「접근성 권한 사용 안내」 창이 뜨고, **동의해야** 설정으로 이동합니다.
- **사용 목적 (영문 신고 예시):** (2026-11-08 고침: 대화방 목록의 🔎 버튼을 덧붙임. 거기서는 화면 글을 읽지 않음)
```
The app helps users who have difficulty typing (e.g., elderly users) reply in KakaoTalk by voice.
The AccessibilityService is used only for these user-initiated purposes:
(1) to show the app's floating button while KakaoTalk is in the foreground (window changes, taps inside KakaoTalk and the layout of the screen — position of the input box and tab names, not message text — are observed only to show/hide the button:
    a microphone button inside a chat room, and a search button on the chat-list tab),
(2) when the user taps the microphone button, to read the text currently visible on the KakaoTalk chat screen so the reply fits the conversation,
(3) after the user dictates a reply and it is shown to them, to type it into the KakaoTalk input box and press its send button.
The search button on the chat-list tab only opens the app's own screen to search chat histories the user had previously exported
into the app with KakaoTalk's own "Export chat" feature; no screen text is read there.
Sending always gives the user a chance to review: a 3-second countdown with a cancel button, an explicit "Send" tap, or a spoken "send" confirmation.
There is no immediate send without review. The app does not navigate KakaoTalk menus, scroll conversations, or perform any action on its own.
It does not read or act on any app other than KakaoTalk.
Screen text and the user's words are sent to the Anthropic Claude API (through the developer's relay server, which stores nothing) only to draft the reply or answer the user's question.
A prominent disclosure and consent dialog is shown before the service can be enabled.
```
- **영상 (필수, 30초~1분, 유튜브 미등록 링크):**
  1. 앱 첫 화면 → '설정 열기' → **공개·동의 창**이 보이게 → 동의
  2. 접근성에서 켜기
  3. 카카오톡 대화방 → '도우미' 버튼 → 말하기 "내일 회의 3시로 하자고 보내 줘"
  4. 다듬은 문장, 3초 카운트다운과 '취소' 버튼이 보이게 → 전송되는 장면

## 심사에서 거절될 수 있는 부분
- 접근성 API로 **다른 앱(카카오톡)에 입력하고 전송**하는 기능 자체가 거절될 수 있습니다. 거절 사유를 보내 주시면 고쳐서 다시 신청하겠습니다.
- 그래도 안 되면 대안이 있습니다. 카카오톡 **알림의 '답장' 기능**으로 보내는 방식이라 접근성이 필요 없습니다. 대신 카톡 위 버튼과 화면 읽기가 빠집니다.
- **카카오 약관**에서 자동 입력·전송을 허용하는지는 별도로 확인이 필요합니다.
- 2026-11-05: 플레이판(`STORE_SAFE`)에서 확인 없는 '바로 보내기', 카톡 메뉴를 대신 누르는 '대화 내보내기', 대화방을 대신 넘기는 '예전 대화 모으기'·'해당 메시지로 이동'을 껐습니다. 남은 우려 목록은 `docs/launch-risks.md`.
