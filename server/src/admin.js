// 관리자 통계: 사용 횟수·사용자 수·AI 요금 (대화 내용은 없음, 폰은 무작위 번호 앞 6자리만)
// 비밀 ADMIN_KEY (Cloudflare 대시보드에서 신부님이 정한 비밀번호) 를 아는 사람만 볼 수 있음

// Claude Haiku 5.5 정가 (1백만 토큰당 달러) · 환율
const PRICE = { in: 0.10, out: 0.50, cacheRead: 0.01, cacheWrite: 0.125 };
const KRW = 1400;
// 토큰 기록이 없는 예전 앱 요청은 1회 평균값으로 어림
const EST_WON = { rewrite: 0.6, answer: 2.7, v1: 0.8, old: 0.8 };

function won(r) {
  if (r.in_tok || r.out_tok || r.cache_read || r.cache_write) {
    const usd = (r.in_tok * PRICE.in + r.out_tok * PRICE.out + r.cache_read * PRICE.cacheRead + r.cache_write * PRICE.cacheWrite) / 1e6;
    return usd * KRW;
  }
  return (EST_WON[r.kind] ?? 1) * r.count;
}

function kst(offsetDays = 0) {
  return new Date(Date.now() + 9 * 3600 * 1000 - offsetDays * 86400 * 1000).toISOString().slice(0, 10);
}

/** 요청 한 번을 날마다 합계에 더함 (토큰은 v2 응답에서) */
export async function countDaily(env, day, kind, u = null) {
  await env.DB.prepare(
    "INSERT INTO daily (day, kind, count, in_tok, out_tok, cache_read, cache_write) VALUES (?, ?, 1, ?, ?, ?, ?) " +
    "ON CONFLICT(day, kind) DO UPDATE SET count = count + 1, in_tok = in_tok + excluded.in_tok, out_tok = out_tok + excluded.out_tok, " +
    "cache_read = cache_read + excluded.cache_read, cache_write = cache_write + excluded.cache_write"
  ).bind(day, kind, u?.input || 0, u?.output || 0, u?.cache_read || 0, u?.cache_write || 0).run();
}

function sameText(a, b) {
  if (typeof a !== "string" || typeof b !== "string" || a.length !== b.length) return false;
  let x = 0;
  for (let i = 0; i < a.length; i++) x |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return x === 0;
}

export async function stats(env, request) {
  if (!env.ADMIN_KEY) return json({ error: "관리자 비밀번호(ADMIN_KEY)가 아직 서버에 없어요." }, 503);
  if (!sameText(request.headers.get("x-admin-key") || "", env.ADMIN_KEY)) return json({ error: "비밀번호가 맞지 않아요." }, 401);

  const today = kst(0), d7 = kst(6), d30 = kst(29);
  const rows = (await env.DB.prepare("SELECT * FROM daily ORDER BY day").all()).results || [];
  const perDay = {};
  const total = { count: 0, rewrite: 0, answer: 0, other: 0, won: 0 };
  for (const r of rows) {
    const d = (perDay[r.day] ||= { day: r.day, count: 0, rewrite: 0, answer: 0, other: 0, won: 0, devices: 0 });
    const k = r.kind === "rewrite" || r.kind === "answer" ? r.kind : "other";
    const w = won(r);
    d.count += r.count; d[k] += r.count; d.won += w;
    total.count += r.count; total[k] += r.count; total.won += w;
  }
  // 날마다 쓴 폰 수 (30일치만 남아 있음)
  const devDays = (await env.DB.prepare(
    "SELECT day, COUNT(*) n FROM usage WHERE device <> '*' GROUP BY day"
  ).all()).results || [];
  for (const r of devDays) (perDay[r.day] ||= { day: r.day, count: 0, rewrite: 0, answer: 0, other: 0, won: 0, devices: 0 }).devices = r.n;

  const active = async (from) => (await env.DB.prepare(
    "SELECT COUNT(DISTINCT device) n FROM usage WHERE device <> '*' AND day >= ?"
  ).bind(from).first())?.n || 0;

  // 폰별 (최근 30일): 번호 앞 6자리만
  const devices = ((await env.DB.prepare(
    "SELECT device, SUM(count) total, COUNT(*) days, MAX(day) last, " +
    "SUM(CASE WHEN day = ? THEN count ELSE 0 END) today FROM usage WHERE device <> '*' AND day >= ? " +
    "GROUP BY device ORDER BY total DESC LIMIT 100"
  ).bind(today, d30).all()).results || []).map((r) => ({ id: r.device.slice(0, 6), total: r.total, days: r.days, last: r.last, today: r.today }));

  const check = (await env.DB.prepare("SELECT verdict, COUNT(*) n FROM device_check GROUP BY verdict").all()).results || [];

  return json({
    today,
    todayStats: perDay[today] || { day: today, count: 0, rewrite: 0, answer: 0, other: 0, won: 0, devices: 0 },
    active: { today: await active(today), d7: await active(d7), d30: await active(d30) },
    total,
    since: rows[0]?.day || today,
    days: Object.values(perDay).sort((a, b) => (a.day < b.day ? 1 : -1)).slice(0, 30),
    devices,
    limits: { device: Number(env.DEVICE_DAILY_LIMIT), global: Number(env.GLOBAL_DAILY_LIMIT) },
    integrity: { mode: env.INTEGRITY_MODE, verdicts: check },
  });
}

function json(o, status = 200) {
  return new Response(JSON.stringify(o), { status, headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" } });
}

export function page() {
  return new Response(PAGE, { headers: { "content-type": "text/html; charset=utf-8", "cache-control": "no-store" } });
}

const PAGE = `<!doctype html>
<html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="robots" content="noindex"><title>말로 답장 사용 현황</title>
<style>
:root{--bg:#F7F4EE;--card:#fff;--ink:#3B1E1E;--mut:#6B625A;--line:#E2D9CC;--y:#FEE500;--g:#2E7D6B}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:16px/1.5 -apple-system,"Apple SD Gothic Neo","Noto Sans KR",sans-serif;padding:16px}
h1{font-size:24px;margin:4px 0 2px}.sub{color:var(--mut);font-size:13px;margin-bottom:14px}
.grid{display:grid;grid-template-columns:repeat(2,1fr);gap:10px}
.card{background:var(--card);border:1px solid var(--line);border-radius:16px;padding:14px}
.k{color:var(--mut);font-size:13px}.v{font-size:26px;font-weight:800}.s{color:var(--mut);font-size:13px}
h2{font-size:17px;margin:22px 0 8px}
table{width:100%;border-collapse:collapse;background:var(--card);border-radius:14px;overflow:hidden;font-size:14px}
th,td{padding:8px 6px;border-bottom:1px solid var(--line);text-align:right}th:first-child,td:first-child{text-align:left}
th{background:#F1ECE4;font-size:12px;color:var(--mut)}
.bar{height:8px;background:var(--y);border-radius:4px;display:inline-block;vertical-align:middle;margin-right:6px}
button,input{font:inherit}input{width:100%;padding:12px;border:1px solid var(--line);border-radius:12px;margin:8px 0}
button{background:var(--ink);color:var(--y);border:0;border-radius:12px;padding:12px 16px;font-weight:700}
.err{color:#B4441F;margin:8px 0}.mono{font-family:ui-monospace,monospace}
</style></head><body>
<h1>말로 답장 사용 현황</h1><div class="sub" id="sub">불러오는 중…</div>
<div id="login" style="display:none" class="card"><b>관리자 비밀번호</b><input id="pw" type="password" autocomplete="current-password"><button id="go">보기</button><div class="err" id="err"></div><div class="s">한 번 넣으면 이 기기에 기억돼요.</div></div>
<div id="main" style="display:none"></div>
<script>
const $=s=>document.querySelector(s);
const n=x=>Math.round(x).toLocaleString('ko-KR');
const wonf=x=>x<10?(Math.round(x*10)/10)+'원':n(x)+'원';
function key(){try{return localStorage.getItem('adminKey')||''}catch(e){return ''}}
async function load(){
  const k=key(); if(!k){showLogin();return}
  const r=await fetch('/admin/stats',{headers:{'x-admin-key':k}});
  const j=await r.json().catch(()=>({error:'읽지 못했어요'}));
  if(!r.ok){try{localStorage.removeItem('adminKey')}catch(e){} showLogin(j.error);return}
  render(j)
}
function showLogin(e){$('#login').style.display='block';$('#main').style.display='none';$('#sub').textContent='';$('#err').textContent=e||''}
$('#go').onclick=()=>{try{localStorage.setItem('adminKey',$('#pw').value.trim())}catch(e){} $('#login').style.display='none';load()};
function render(j){
  $('#sub').textContent='오늘 '+j.today+' · 기록 시작 '+j.since+' · 대화 내용은 저장하지 않아요';
  const t=j.todayStats, max=Math.max(1,...j.days.map(d=>d.count));
  const card=(k,v,s)=>'<div class="card"><div class="k">'+k+'</div><div class="v">'+v+'</div><div class="s">'+(s||'')+'</div></div>';
  let h='<div class="grid">'+
    card('오늘 사용',n(t.count)+'번','다듬기 '+n(t.rewrite)+' · 찾기 '+n(t.answer)+(t.other?' · 기타 '+n(t.other):''))+
    card('오늘 쓴 사람',n(j.active.today)+'명','7일 '+n(j.active.d7)+'명 · 30일 '+n(j.active.d30)+'명')+
    card('누적 사용',n(j.total.count)+'번','다듬기 '+n(j.total.rewrite)+' · 찾기 '+n(j.total.answer))+
    card('AI 요금',wonf(t.won)+' (오늘)','누적 '+wonf(j.total.won))+
    '</div>';
  h+='<h2>날마다</h2><table><tr><th>날짜</th><th>사용</th><th>다듬기</th><th>찾기</th><th>사람</th><th>요금</th></tr>'+
    j.days.map(d=>'<tr><td>'+d.day.slice(5)+'</td><td><span class="bar" style="width:'+Math.max(2,40*d.count/max)+'px"></span>'+n(d.count)+'</td><td>'+n(d.rewrite)+'</td><td>'+n(d.answer)+'</td><td>'+n(d.devices)+'</td><td>'+wonf(d.won)+'</td></tr>').join('')+'</table>';
  h+='<h2>사용자별 (최근 30일)</h2><div class="s" style="margin-bottom:6px">폰마다 앱이 만든 무작위 번호 앞자리. 누구인지는 알 수 없어요.</div>'+
    '<table><tr><th>번호</th><th>오늘</th><th>30일 합계</th><th>쓴 날</th><th>하루 평균</th><th>마지막</th></tr>'+
    j.devices.map(d=>'<tr><td class="mono">'+d.id+'</td><td>'+n(d.today)+'</td><td>'+n(d.total)+'</td><td>'+d.days+'일</td><td>'+(Math.round(d.total/d.days*10)/10)+'</td><td>'+d.last.slice(5)+'</td></tr>').join('')+'</table>';
  h+='<div class="s" style="margin-top:16px">한도: 폰 하나 하루 '+n(j.limits.device)+'번 · 전체 하루 '+n(j.limits.global)+'번 · 진짜 앱 확인 '+j.integrity.mode+' ('+j.integrity.verdicts.map(v=>v.verdict+' '+v.n).join(', ')+')</div>';
  h+='<div style="margin-top:16px"><button onclick="load()">새로 고침</button> <button onclick="localStorage.removeItem(\\'adminKey\\');showLogin()" style="background:#fff;color:var(--ink);border:1px solid var(--line)">로그아웃</button></div>';
  $('#main').innerHTML=h;$('#main').style.display='block';$('#login').style.display='none'
}
load();
</script></body></html>`;
