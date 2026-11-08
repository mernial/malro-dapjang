/**
 * 「말로 답장」 중계 서버.
 * 앱은 Anthropic SDK 의 주소만 이 서버로 바꿔 Messages API 요청을 그대로 보냄.
 * 서버는 (1) 요청이 우리 앱 모양인지 확인 (2) 폰별·전체 하루 한도를 세고
 * (3) 서버에만 있는 Claude 키를 붙여 Anthropic 으로 넘긴 뒤 답을 그대로 돌려줌.
 * 대화 내용(요청·응답 본문)은 저장하거나 기록하지 않음. 하루 요청 수만 셈.
 */

import * as P from "./prompts.js";
import { countDaily, stats, page } from "./admin.js";

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


// ───────── Play Integrity (구글이 '플레이에서 받은 진짜 앱'인지 확인) ─────────
// 모드: off(안 봄) · log(확인해서 기록만, 막지 않음) · enforce(진짜 앱이 아니면 막음)

async function sha256hex(text) {
  const buf = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function b64url(bytes) {
  let s = typeof bytes === "string" ? btoa(bytes) : btoa(String.fromCharCode(...new Uint8Array(bytes)));
  return s.replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

let googleToken = null; // { value, exp }

/** 서비스 계정으로 구글 API 접근 토큰 받기 (1시간짜리, 메모리에 잠깐 보관) */
async function googleAccessToken(env) {
  const now = Math.floor(Date.now() / 1000);
  if (googleToken && googleToken.exp - 60 > now) return googleToken.value;
  const sa = JSON.parse(env.GOOGLE_SA_JSON);
  const header = b64url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claim = b64url(JSON.stringify({
    iss: sa.client_email,
    scope: "https://www.googleapis.com/auth/playintegrity",
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600,
  }));
  const pem = sa.private_key.replace(/-----[^-]+-----/g, "").replace(/\s+/g, "");
  const der = Uint8Array.from(atob(pem), (c) => c.charCodeAt(0));
  const key = await crypto.subtle.importKey("pkcs8", der, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  const sig = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(`${header}.${claim}`));
  const res = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: `grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=${header}.${claim}.${b64url(sig)}`,
  });
  const j = await res.json();
  if (!j.access_token) throw new Error("google token: " + (j.error || res.status));
  googleToken = { value: j.access_token, exp: now + (j.expires_in || 3600) };
  return googleToken.value;
}

/** 토큰을 구글에 물어 판정. 결과 글자: ok / no-token / bad-app / bad-hash / old / error:… */
async function checkIntegrity(env, token, nonce) {
  if (!token || !nonce) return "no-token";
  try {
    const access = await googleAccessToken(env);
    const res = await fetch(`https://playintegrity.googleapis.com/v1/${env.PACKAGE_NAME}:decodeIntegrityToken`, {
      method: "POST",
      headers: { authorization: `Bearer ${access}`, "content-type": "application/json" },
      body: JSON.stringify({ integrity_token: token }),
    });
    const j = await res.json();
    const p = j.tokenPayloadExternal;
    if (!p) return "error:" + (j.error?.status || res.status);
    const req = p.requestDetails || {};
    if (req.requestPackageName !== env.PACKAGE_NAME) return "bad-app";
    if (req.requestHash !== (await sha256hex(nonce))) return "bad-hash";
    if (Math.abs(Date.now() - Number(req.timestampMillis || 0)) > 10 * 60 * 1000) return "old";
    if (p.appIntegrity?.appRecognitionVerdict !== "PLAY_RECOGNIZED") return "bad-app:" + (p.appIntegrity?.appRecognitionVerdict || "none");
    return "ok";
  } catch (e) {
    return "error:" + String(e.message || e).slice(0, 60);
  }
}

/** 폰별로 6시간에 한 번만 구글에 물음 (판정은 D1에 보관) */
async function integrityGate(env, request, device) {
  const mode = env.INTEGRITY_MODE || "off";
  if (mode === "off" || !env.GOOGLE_SA_JSON) return { ok: true };
  const now = Date.now();
  const prev = await env.DB.prepare("SELECT verdict, checked_at FROM device_check WHERE device = ?").bind(device).first();
  if (prev && prev.verdict === "ok" && now - prev.checked_at < 6 * 3600 * 1000) return { ok: true };
  const verdict = await checkIntegrity(env, request.headers.get("x-malro-integrity"), request.headers.get("x-malro-nonce"));
  await env.DB.prepare(
    "INSERT INTO device_check (device, verdict, checked_at) VALUES (?, ?, ?) " +
    "ON CONFLICT(device) DO UPDATE SET verdict = excluded.verdict, checked_at = excluded.checked_at"
  ).bind(device, verdict, now).run();
  if (verdict === "ok" || mode !== "enforce") return { ok: true };
  return { ok: false, verdict };
}


// ───────── v2: 재료로 요청 만들기 (지시문은 prompts.js) ─────────
const MESSAGE_FORMAT = {
  type: "json_schema",
  schema: {
    type: "object",
    properties: { message: { type: "string" } },
    required: ["message"],
    additionalProperties: false,
  },
};

function str(x, max) {
  return typeof x === "string" ? x.slice(0, max) : "";
}

/** 요청 본문 또는 오류 글자 */
function buildV2(route, input, env) {
  const base = { model: env.ALLOWED_MODEL, max_tokens: 4000 };
  const sys = (text) => [{ type: "text", text, cache_control: { type: "ephemeral" } }];
  if (route === "/v2/rewrite") {
    const mode = String(input.mode || "");
    if (!P.MODES[mode]) return "알 수 없는 말투입니다.";
    const spoken = str(input.spoken, 3000);
    if (!spoken.trim()) return "말한 내용이 비어 있습니다.";
    const style = Array.isArray(input.myStyle) ? input.myStyle.slice(0, 30).map((x) => str(x, 300)) : [];
    return {
      ...base,
      // 정해진 형식 {"message": "..."} 으로만 받아, AI 가 생각한 내용이 카톡으로 나가지 않게
      output_config: { effort: "medium", format: MESSAGE_FORMAT },
      system: sys(P.system(mode)),
      messages: [{ role: "user", content: P.user(mode, spoken, str(input.screenText, 20000), str(input.myName, 100), str(input.myRole, 100), style) }],
    };
  }
  const question = str(input.question, 1000);
  if (!question.trim()) return "질문이 비어 있습니다.";
  return {
    ...base,
    output_config: { effort: "low" },
    system: sys(P.ASK_SYSTEM),
    // scope "all": 저장된 여러 방에서 한꺼번에 찾기 (앱이 방마다 "[대화방: 이름]" 머리글을 붙여 보냄)
    messages: [{ role: "user", content: P.askUser(question, str(input.room, 200), str(input.transcript, 200000), input.scope === "all") }],
  };
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (request.method === "GET" && url.pathname === "/") {
      return new Response("malro-dapjang relay ok", { headers: { "content-type": "text/plain" } });
    }
    // 관리자 사용 현황 (비밀번호 ADMIN_KEY)
    if (request.method === "GET" && url.pathname === "/admin") return page();
    if (request.method === "GET" && url.pathname === "/admin/stats") return stats(env, request);
    if (request.method !== "POST") return err(404, "not_found_error", "not found");
    const route = url.pathname;
    if (route !== "/v1/messages" && route !== "/v2/rewrite" && route !== "/v2/answer") {
      return err(404, "not_found_error", "not found");
    }

    // (1) 우리 앱에서 온 요청인지 (가벼운 확인 + 진짜 앱 확인)
    const device = request.headers.get("x-malro-device") || "";
    const app = request.headers.get("x-malro-app") || "";
    // 시험 요청(에뮬레이터 확인·개발자 시험): 사용 현황에서 빼고, 폰별 한도는 '~test' 한 칸에 함께 셈
    const isTest = request.headers.get("x-malro-test") === "1" || /^malro-dapjang\/(test|promo)/.test(app);
    if (!/^[0-9a-f-]{32,40}$/i.test(device) || !app.startsWith("malro-dapjang/")) {
      return err(403, "permission_error", "허용되지 않은 요청입니다.");
    }
    if (!env.ANTHROPIC_API_KEY) {
      return err(503, "api_error", "서버 준비 중입니다. 잠시 뒤에 다시 해 주세요.");
    }
    let input;
    try {
      input = await request.json();
    } catch {
      return err(400, "invalid_request_error", "잘못된 요청입니다.");
    }

    // (1-2) 플레이에서 받은 진짜 앱인지 (모드에 따라 기록만 하거나 막음)
    const gate = await integrityGate(env, request, device);
    if (!gate.ok) {
      return err(403, "permission_error", "플레이 스토어에서 받은 최신 앱에서만 쓸 수 있어요. 앱을 업데이트해 주세요.");
    }

    // (1-3) 보낼 요청 만들기. v2 는 앱이 재료만 보내고 지시문은 서버가 붙임 (앱을 뜯어도 지시문이 안 보이게)
    let body;
    if (route === "/v1/messages") {
      // 예전 앱(1.170 이하): 앱이 만든 요청을 그대로. 모두 새 앱으로 바뀌면 V1_ALLOWED=false 로 막음
      if (env.V1_ALLOWED === "false") return err(403, "permission_error", "앱을 최신 버전으로 업데이트해 주세요.");
      body = input;
      if (body.model !== env.ALLOWED_MODEL) return err(400, "invalid_request_error", "허용되지 않은 모델입니다.");
    } else {
      body = buildV2(route, input, env);
      if (typeof body === "string") return err(400, "invalid_request_error", body);
    }
    body.max_tokens = Math.min(Number(body.max_tokens) || 1000, Number(env.MAX_TOKENS));
    if (body.stream) body.stream = false;

    // (2) 하루 한도: 전체 → 폰별
    const day = kstDay();
    // 전체 수는 device='*' 한 줄에 셈 (사용자가 늘어도 읽는 양이 그대로)
    const total = await env.DB.prepare(
      "INSERT INTO usage (device, day, count) VALUES ('*', ?, 1) " +
      "ON CONFLICT(device, day) DO UPDATE SET count = count + 1 RETURNING count"
    ).bind(day).first();
    if ((total?.count ?? 0) > Number(env.GLOBAL_DAILY_LIMIT)) {
      return err(429, "rate_limit_error", "오늘은 사용량이 많아 잠시 쉬어요. 내일 다시 해 주세요.");
    }
    const row = await env.DB.prepare(
      "INSERT INTO usage (device, day, count) VALUES (?, ?, 1) " +
      "ON CONFLICT(device, day) DO UPDATE SET count = count + 1 RETURNING count"
    ).bind(isTest ? "~test" : device, day).first();
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
    const search = route === "/v1/messages" ? url.search : "?beta=true";
    const upstream = await fetch(ANTHROPIC + "/v1/messages" + search, {
      method: "POST",
      headers,
      body: JSON.stringify(body),
    });

    if (route === "/v1/messages") {
      if (!isTest) await countDaily(env, day, "v1").catch(() => {});
      return new Response(upstream.body, {
        status: upstream.status,
        headers: { "content-type": upstream.headers.get("content-type") || "application/json" },
      });
    }
    // v2: 앱에 필요한 것만 (글, 끝난 이유, 토큰 수)
    const j = await upstream.json().catch(() => null);
    if (!upstream.ok || !j) {
      const status = upstream.status === 429 ? 429 : upstream.status >= 500 ? 502 : upstream.status;
      return err(status, j?.error?.type || "api_error", j?.error?.message || "AI 서버 오류");
    }
    const u = j.usage || {};
    if (!isTest) await countDaily(env, day, route === "/v2/rewrite" ? "rewrite" : "answer", {
      input: u.input_tokens || 0, output: u.output_tokens || 0,
      cache_read: u.cache_read_input_tokens || 0, cache_write: u.cache_creation_input_tokens || 0,
    }).catch(() => {});
    return new Response(JSON.stringify({
      text: (j.content || []).filter((c) => c.type === "text").map((c) => c.text).join(""),
      stop_reason: j.stop_reason,
      usage: {
        input: u.input_tokens || 0,
        cache_read: u.cache_read_input_tokens || 0,
        cache_write: u.cache_creation_input_tokens || 0,
        output: u.output_tokens || 0,
      },
    }), { headers: { "content-type": "application/json" } });
  },

  // 매일 새벽: 30일 지난 사용 기록 지움
  async scheduled(_event, env) {
    const cutoff = new Date(Date.now() + 9 * 3600 * 1000 - 30 * 86400 * 1000).toISOString().slice(0, 10);
    await env.DB.prepare("DELETE FROM usage WHERE day < ?").bind(cutoff).run();
    await env.DB.prepare("DELETE FROM device_check WHERE checked_at < ?").bind(Date.now() - 30 * 86400 * 1000).run();
  },
};
