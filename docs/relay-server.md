# 중계 서버 (2026-11-07부터)

앱을 **설치만 하면 바로** 쓰게 하려고, Claude 키를 폰이 아니라 서버에만 둡니다.

```
폰(말로 답장) ── 요청(+설치 번호, +진짜 앱 표시) ──▶ 중계 서버 ── 서버의 키 ──▶ Claude API
```

| 항목 | 값 |
|---|---|
| 주소 | `https://malro-dapjang-relay.mernial.workers.dev` |
| 위치 | 신부님 Cloudflare 계정(가톨릭그램과 같은 계정) · Workers 무료 요금제 |
| 코드 | 이 저장소 `server/` (`src/index.js`, `wrangler.toml`, `schema.sql`) |
| Claude 키 | Cloudflare 비밀 `ANTHROPIC_API_KEY` (Claude Console 키 이름 `malro-relay2`). **저장소·앱·대화에 없음** |
| 사용 수 DB | Cloudflare D1 `malro-dapjang-usage` (설치 번호별 하루 횟수, 진짜 앱 판정만. 30일 뒤 지움) |
| 배포 | 작업 환경 비밀(Cloudflare 토큰 `malro-dapjang-server`, 2027-11 만료)로 `npx wrangler deploy` |

## 안전장치
- 우리 앱 모양의 요청만 (`x-malro-device`, `x-malro-app` 머리글), 모델은 `claude-haiku-5-5`만, 답 길이 상한 4000토큰
- 폰 한 대 하루 **150번**, 모든 폰 합쳐 하루 **5000번** (`wrangler.toml`의 숫자를 바꾸고 다시 배포)
- **진짜 앱 확인(Play Integrity)**: `INTEGRITY_MODE` = `off` · `log`(기록만, 지금) · `enforce`(막음)
- Claude Console **월 한도**가 최후의 안전장치
- 대화 내용은 저장·기록하지 않음. Cloudflare **Logs는 꺼 둠**(켜지 말 것)

## 앱 쪽
- 폰에 API 키가 **없으면** 서버로, **있으면**(가족·예전 방식) 그 키로 바로
- 준비 단계: 접근성 → 마이크 (2단계, 설정 코드 없음)
- `BuildConfig.RELAY_URL`, `BuildConfig.CLOUD_PROJECT_NUMBER`(Play Integrity, 0이면 끔) — `android/app/build.gradle.kts`

## 진짜 앱 확인 켜는 순서 (남은 일)
1. 신부님: Play Console → **테스트 및 출시 → 앱 무결성 → Play Integrity API → Cloud 프로젝트 연결** (플레이 업로드용 서비스 계정이 있는 프로젝트를 고르면 편함). 화면의 **프로젝트 번호**(숫자, 비밀 아님)를 Claude에게 알려 줌
2. Claude: `CLOUD_PROJECT_NUMBER`에 번호 넣고 앱 배포
3. 신부님: 그 프로젝트의 서비스 계정 JSON(iCloud `말로답장`의 플레이 서비스 계정 키)을 Cloudflare `malro-dapjang-relay` → 설정 → 변수 및 비밀 → **비밀** `GOOGLE_SA_JSON`에 통째로 붙여 넣기
4. Claude: D1 `device_check`의 판정이 `ok`로 쌓이는지 확인 → `INTEGRITY_MODE = "enforce"`로 바꿔 배포

## 문제가 생기면
| 증상 | 확인 |
|---|---|
| "AI 서버 연결에 문제가 있어요" | Cloudflare 비밀 `ANTHROPIC_API_KEY`가 있는지, Claude Console에서 키가 살아 있는지 |
| "오늘 쓸 수 있는 횟수를 다 썼어요" | 하루 한도 도달. 필요하면 `DEVICE_DAILY_LIMIT` 늘리기 |
| "서버 준비 중" | `ANTHROPIC_API_KEY`가 없음 |
| Claude 잔액 0 | Claude Console에서 충전 (자동 충전 권장) |
