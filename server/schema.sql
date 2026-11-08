-- 하루 요청 수만 셈. 대화 내용은 저장하지 않음
CREATE TABLE IF NOT EXISTS usage (
  device TEXT NOT NULL,      -- 앱이 설치 때 만든 무작위 번호 (개인을 알 수 없음)
  day TEXT NOT NULL,         -- YYYY-MM-DD (한국 시간)
  count INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (device, day)
);
CREATE INDEX IF NOT EXISTS usage_day ON usage(day);

-- 진짜 앱 확인 결과 (폰별 마지막 판정만, 30일 지나면 지움)
CREATE TABLE IF NOT EXISTS device_check (
  device TEXT PRIMARY KEY,
  verdict TEXT NOT NULL,      -- ok / no-token / bad-app:… / error:…
  checked_at INTEGER NOT NULL -- 밀리초
);

-- 날마다 합계 (폰 번호 없이 숫자만, 오래 보관해 누적 사용량을 봄)
CREATE TABLE IF NOT EXISTS daily (
  day TEXT NOT NULL,          -- YYYY-MM-DD (한국 시간)
  kind TEXT NOT NULL,         -- rewrite(다듬기) / answer(찾기) / v1(예전 앱)
  count INTEGER NOT NULL DEFAULT 0,
  in_tok INTEGER NOT NULL DEFAULT 0,
  out_tok INTEGER NOT NULL DEFAULT 0,
  cache_read INTEGER NOT NULL DEFAULT 0,
  cache_write INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (day, kind)
);

-- 사용 현황에서 뺄 시험 기기 (2026-11-08 이전 개발자 시험·에뮬레이터 번호). 이후 시험은 x-malro-test 머리글로 '~test' 에 셈
CREATE TABLE IF NOT EXISTS test_device (device TEXT PRIMARY KEY);
