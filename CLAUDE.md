# 이 저장소에서 일하는 Claude에게

- **항상 한국어로** 답하세요. 사용자는 윤호요셉 신부님이고, 앱은 부모님 같은 어르신이 쓰십니다. 쉬운 말로, 짧게.
- 비밀 값(API 키, 서명 키, 서비스 계정 키, 설정 코드)은 **절대 커밋하거나 대화에 다시 적지 마세요.** GitHub 비밀(`SIGNING_KEY_B64`, `PLAY_SERVICE_ACCOUNT_JSON`)과 사용자의 iCloud `말로답장` 폴더에만 있습니다.

- **기능을 바꿀 때마다 카카오·구글 정책 점검을 하고, 걸리거나 심사에 안 될 것 같은 것은 업데이트 전에 사용자에게 알릴 것** (사용자 요청 2026-11-08, 11-10). 기준과 기록: `docs/policy-check.md`.

## 「말로 답장」 (android/)

카카오톡 위에 뜨는 🎤 버튼으로 말하면 Claude(Haiku 5.5)가 말투를 다듬어 카톡으로 보내 주는 안드로이드 앱. 전체 기능·주소·요금·문제 해결은 `docs/talk-helper-summary.md`(PDF: `docs/말로답장_전체정리.pdf`)에 정리돼 있으니 먼저 읽으세요.

- 패키지 `kr.yunho.talkhelper`, Kotlin, AppCompat 없이 기본 Activity. 플레이버 `play`(플레이 스토어판, 지금 쓰는 것) / `github`(예전 직접 설치판).
- 주요 파일: `KakaoAccessibilityService.kt`(떠 있는 버튼·동그라미 메뉴·카톡 화면 읽기/입력/전송·대화 내보내기 자동화), `HelperActivity.kt`(카톡 위 카드), `AskActivity.kt`(대화에서 찾기·내보낸 대화 받기), `MainActivity.kt`(첫 화면, 제목을 길게 누르면 숨은 설정), `core/`(Claude 호출·프롬프트·순수 로직, JVM 단위 테스트 있음).
- 모델: 다듬기·찾기 모두 `claude-haiku-5-5` (Sonnet은 쓰지 않기로 사용자가 결정).
- 테스트: `android/app/src/test/java/kr/yunho/talkhelper/core/` (CI의 `testGithubDebugUnitTest`).
- **중계 서버**(2026-11-07~): `server/` Cloudflare Workers `malro-dapjang-relay.mernial.workers.dev`. 폰에 키가 없으면 서버가 서버 비밀의 Claude 키로 대신 부름 → 설치만 하면 바로 사용. 자세히 `docs/relay-server.md`. Cloudflare Logs는 켜지 말 것.
- **에뮬레이터 확인**: `claude/**` 브랜치에 앱을 바꿔 push하면 `crash-check.yml`이 카드를 열어 보고 첫 화면·간단 설정·안내 띠 사진을 `ui-shots` 브랜치에 올림. 카드 고칠 때마다 확인할 것.

## 빌드와 배포

- 이 저장소(`mernial/malro-dapjang`)는 2026-11-05에 `mernial/catholic-shorts`에서 옮겨 왔음. 앱 일은 이제 여기서만 함.
- 브랜치에 push → GitHub Actions(`.github/workflows/android.yml`)가 테스트·빌드 → Releases(`talk-helper` 태그)에 APK/AAB → **플레이 내부 테스트에 자동 업로드**. 버전 = 실행 번호 + 100 (1.N). 예전 저장소에서 1.84까지 올렸기 때문.
- 자동 서명·업로드에는 이 저장소의 GitHub 비밀 `SIGNING_KEY_B64`, `PLAY_SERVICE_ACCOUNT_JSON`이 필요함 (사용자가 직접 넣음).
- 개인정보처리방침·설치 안내 페이지는 **예전 저장소** catholic-shorts의 main 브랜치 GitHub Pages(`talk-helper-privacy.html`, `talk-helper-install.html`)에 그대로 둠. 플레이에 그 주소가 등록돼 있으므로 옮기지 말 것.
- 사용자는 폭넓게 권한을 주었고, 지금까지 작업 브랜치에서 PR을 만들어 main에 합쳐 왔음.

## 남은 일

- 어머니(S26) 설치: 테스터 등록은 됨, 플레이 "다운로드할 수 없습니다" — 예전 '카톡 도우미'(서명이 다른 같은 패키지, 보안 폴더 포함) 잔존 여부 확인 중.
- 정식 출시: **비공개 테스트 승인됨(2026-11-07)** → 테스터 12명 × 14일 → 프로덕션 신청. 계정·앱 판매자 상태는 '비판매자'(유료 전환 때 바꿈, `docs/sales/pricing.md` 8). 카카오 문의 답변 대기(2차, 조항을 짚은 문의 2026-11-06 보냄: `docs/kakao-inquiry.md`). 우려 목록 `docs/launch-risks.md`, 스토어 자료 `docs/play-store/`.
- 판매 준비(나중): `docs/sales/`. 중계 서버는 무료로 운영 중(구독 확인·결제는 아직 없음).
- 진짜 앱 확인(Play Integrity): 서버·앱 코드는 들어감, 지금 `log`. 켜는 순서는 `docs/relay-server.md`.
- 플레이판(`STORE_SAFE`)은 카톡 자동 조작(내보내기 누르기·대화방 넘기기)과 '바로 보내기'를 끔. 찾기는 그 방에서 내보낸 대화를 씀(`Rooms`, 고유 번호·방 이름·말한 사람 저장). 방을 못 알아보면 저장된 모든 대화에서 찾음(`ChatSearch.excerptMany`, 서버 `scope: "all"`). 대화방 목록에선 🔎 버튼(모든 대화에서 찾기).
- 말투는 방마다 기억(`Prefs.modeFor/setModeFor`, 열쇠는 `RoomMatch.norm(방 이름)`). 동그라미 메뉴는 두 단계(말투 폴더 → 다섯 말투), 안내 띠·설명 알약을 피해 놓음(`MenuLayout.place(avoid)`).
- 듣기 신호 소리는 `res/raw/listen_*.ogg`(직접 합성, `Sounds.kt`).
- **카카오 2차 답변(2026-11-10): 비공식 프로그램·자동 조작 불허 → 키보드(IME) 방식으로 전환 중.** `KeyboardService.kt`(전체 자판 두벌식/천지인/영어/숫자 — 조합은 `core/Hangul.kt`, 위 단축키 줄 🎤 말로 답장·🔎 찾기·말투·⇄ 자판 바꾸기, 배열은 삼성 키보드와 같게(폴드에서도 나누지 않음). 보내기는 사용자가 직접). 1단계: 접근성과 함께 둠 → 2단계: 플레이판에서 접근성 빼기. 3차 문의 글 `docs/kakao-inquiry.md`.
