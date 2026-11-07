/**
 * 「말로 답장」 중계 서버.
 * 앱은 Anthropic SDK 의 주소만 이 서버로 바꿔 Messages API 요청을 그대로 보냄.
 * 서버는 (1) 요청이 우리 앱 모양인지 확인 (2) 폰별·전체 하루 한도를 세고
 * (3) 서버에만 있는 Claude 키를 붙여 Anthropic 으로 넘긴 뒤 답을 그대로 돌려줌.
 * 대화 내용(요청·응답 본문)은 저장하거나 기록하지 않음. 하루 요청 수만 셈.
 */

const ANTHROPIC = "https://api.anthropic.com";

function err(status, type, message) {
  // Anthropic 오류 모양 그대로 (앱의 SDK 가 알아들음)
  return new Response(JSON.stringify({ type: "error", error: { type, message } }), {
    status,
    headers: { "content-type": "application/json" },
  });
}

function kstDay() {
  return new Date(Date.now() + 9 * 3600 * 1000).toISOString().slice(0, 10);
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (request.method === "GET" && url.pathname === "/") {
      return new Response("malro-dapjang relay ok", { headers: { "content-type": "text/plain" } });
    }
    if (request.method !== "POST" || url.pathname !== "/v1/messages") {
      return err(404, "not_found_error", "not found");
    }

    // (1) 우리 앱에서 온 요청인지 (가벼운 확인. 진짜 앱 확인(Play Integrity)은 다음 단계)
    const device = request.headers.get("x-malro-device") || "";
    const app = request.headers.get("x-malro-app") || "";
    if (!/^[0-9a-f-]{32,40}$/i.test(device) || !app.startsWith("malro-dapjang/")) {
      return err(403, "permission_error", "허용되지 않은 요청입니다.");
    }
    if (!env.ANTHROPIC_API_KEY) {
      return err(503, "api_error", "서버 준비 중입니다. 잠시 뒤에 다시 해 주세요.");
    }

    let body;
    try {
      body = await request.json();
    } catch {
      return err(400, "invalid_request_error", "잘못된 요청입니다.");
    }
    if (body.model !== env.ALLOWED_MODEL) {
      return err(400, "invalid_request_error", "허용되지 않은 모델입니다.");
    }
    body.max_tokens = Math.min(Number(body.max_tokens) || 1000, Number(env.MAX_TOKENS));
    if (body.stream) body.stream = false;

    // (2) 하루 한도: 전체 → 폰별
    const day = kstDay();
    const total = await env.DB.prepare("SELECT COALESCE(SUM(count),0) AS n FROM usage WHERE day = ?").bind(day).first();
    if ((total?.n ?? 0) >= Number(env.GLOBAL_DAILY_LIMIT)) {
      return err(429, "rate_limit_error", "오늘은 사용량이 많아 잠시 쉬어요. 내일 다시 해 주세요.");
    }
    const row = await env.DB.prepare(
      "INSERT INTO usage (device, day, count) VALUES (?, ?, 1) " +
      "ON CONFLICT(device, day) DO UPDATE SET count = count + 1 RETURNING count"
    ).bind(device, day).first();
    if ((row?.count ?? 0) > Number(env.DEVICE_DAILY_LIMIT)) {
      return err(429, "rate_limit_error", "오늘 쓸 수 있는 횟수를 다 썼어요. 내일 다시 해 주세요.");
    }

    // (3) Anthropic 으로 넘김 (키는 서버 비밀에서)
    const headers = {
      "content-type": "application/json",
      "x-api-key": env.ANTHROPIC_API_KEY,
      "anthropic-version": request.headers.get("anthropic-version") || "2023-06-01",
    };
    const beta = request.headers.get("anthropic-beta");
    if (beta) headers["anthropic-beta"] = beta;

    const upstream = await fetch(ANTHROPIC + "/v1/messages" + url.search, {
      method: "POST",
      headers,
      body: JSON.stringify(body),
    });
    return new Response(upstream.body, {
      status: upstream.status,
      headers: { "content-type": upstream.headers.get("content-type") || "application/json" },
    });
  },

  // 매일 새벽: 30일 지난 사용 기록 지움
  async scheduled(_event, env) {
    const cutoff = new Date(Date.now() + 9 * 3600 * 1000 - 30 * 86400 * 1000).toISOString().slice(0, 10);
    await env.DB.prepare("DELETE FROM usage WHERE day < ?").bind(cutoff).run();
  },
};
