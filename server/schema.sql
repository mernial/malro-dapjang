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
