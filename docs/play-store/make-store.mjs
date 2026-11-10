// 말로 답장 스토어 그림: 아이콘 512 · 그래픽 1024x500 · 스크린샷 1080x1920
import { createRequire } from 'module'; const { chromium } = createRequire(import.meta.url)('playwright');
import fs from 'fs';
const D = process.cwd(), OUT = process.argv[2];
const img = f => 'data:image/png;base64,' + fs.readFileSync(f).toString('base64');
const FONT = `@font-face{font-family:N;src:url(file://${D}/NanumSquareRoundR.woff2);font-weight:400}
@font-face{font-family:N;src:url(file://${D}/NanumSquareRoundB.woff2);font-weight:700}
@font-face{font-family:N;src:url(file://${D}/NanumSquareRoundEB.woff2);font-weight:800}
*{box-sizing:border-box}body{margin:0;font-family:N,sans-serif;color:#432725}`;
const ICON = (s, r = 0) => `<svg width="${s}" height="${s}" viewBox="0 0 108 108" style="border-radius:${r}px;display:block">
<rect width="108" height="108" fill="#F6EEDC"/><circle cx="54" cy="54" r="33" fill="#432725"/>
<g fill="#F8D978"><rect x="37.5" y="49" width="5" height="10" rx="2.5"/><rect x="45.5" y="44" width="5" height="20" rx="2.5"/>
<rect x="51.5" y="39" width="5" height="30" rx="2.5"/><rect x="58.5" y="44" width="5" height="20" rx="2.5"/><rect x="65.5" y="49" width="5" height="10" rx="2.5"/></g></svg>`;
// 휴대폰 틀 안에 사진 (위에서 cropTop px 부터, 너비 860 로)
const phone = (src, cropTop = 0, extra = '') => `<div class="ph"><div class="scr" style="background-image:url(${src});background-position:0 -${cropTop * 860 / 1080}px">${extra}</div></div>`;
const page = (title, sub, body) => `<html><head><style>${FONT}
.s{width:1080px;height:1920px;background:#F8F7F3;position:relative;overflow:hidden}
.h{position:absolute;top:120px;left:0;right:0;text-align:center}
.t{font-size:76px;font-weight:800;letter-spacing:-2px;line-height:1.2}
.u{font-size:38px;font-weight:700;color:#8A7F76;margin-top:22px}
.ph{position:absolute;left:90px;top:470px;width:900px;height:1600px;border-radius:64px;background:#2b211f;padding:20px;box-shadow:0 30px 80px rgba(67,39,37,.18)}
.scr{width:860px;height:1560px;border-radius:46px;background-size:860px auto;background-repeat:no-repeat;position:relative;overflow:hidden;background-color:#F8F7F3}
</style></head><body><div class="s"><div class="h"><div class="t">${title}</div><div class="u">${sub}</div></div>${body}</div></body></html>`;
const shots = JSON.parse(fs.readFileSync(D + '/shots.json'));
const browser = await chromium.launch();
const p = await browser.newPage({ deviceScaleFactor: 1 });
async function snap(html, w, h, file) {
  await p.setViewportSize({ width: w, height: h });
  await p.setContent(html); await p.evaluate(() => document.fonts.ready);
  await p.screenshot({ path: OUT + '/' + file, clip: { x: 0, y: 0, width: w, height: h } });
  console.log('made', file);
}
await snap(`<html><head><style>${FONT}</style></head><body>${ICON(512)}</body></html>`, 512, 512, 'icon-512.png');
await snap(`<html><head><style>${FONT}
#f{width:1024px;height:500px;background:#F8F7F3;display:flex;align-items:center;padding:0 80px;gap:60px}
.pill{display:inline-block;background:#432725;color:#F8D978;border-radius:30px;padding:8px 24px;font-size:26px;font-weight:800}
.t1{font-size:54px;font-weight:800;line-height:1.2;letter-spacing:-2px;margin-top:18px}
.t2{font-size:28px;font-weight:700;color:#8A7F76;margin-top:16px}
.c{display:flex;gap:10px;margin-top:22px}.c span{background:#fff;border:2px solid #D8C9B9;border-radius:22px;padding:6px 18px;font-size:23px;font-weight:700}
.c span.on{background:#F8D978;border-color:#F8D978}
</style></head><body><div id="f"><div style="flex:none;border-radius:64px;overflow:hidden;box-shadow:0 14px 40px rgba(67,39,37,.18)">${ICON(280)}</div>
<div><span class="pill">말로 답장</span><div class="t1">말하면,<br>내 말투로 답장해요</div><div class="t2">말로 쓰는 답장 키보드 · 보내기는 직접</div>
<div class="c"><span class="on">정중하게</span><span>친구처럼</span><span>업무 연락</span></div></div></div></body></html>`, 1024, 500, 'feature-1024x500.png');
// 1. 말하면 다듬어서 입력칸에 — 흔한 대화 화면(특정 메신저 모양 아님) + 실제 키보드 사진
const kb = img(shots.keyboard), kbTop = shots.keyboardTop;   // 키보드가 시작되는 y (1080x2400 기준)
const chat = `<div style="position:absolute;inset:0;background:#fff">
 <div style="height:150px;border-bottom:2px solid #eee;display:flex;align-items:flex-end;padding:0 40px 26px;font-size:36px;font-weight:800">‹&nbsp;&nbsp;가족 대화방</div>
 <div style="padding:40px;display:flex;flex-direction:column;gap:22px">
  <div style="align-self:flex-start;background:#F1EEEA;border-radius:28px;padding:22px 30px;font-size:34px;max-width:80%">내일 저녁 같이 먹을 수 있어요?</div>
 </div>
 <div style="position:absolute;left:150px;right:60px;top:430px;background:#432725;color:#F8D978;border-radius:30px;padding:24px 30px;font-size:34px;font-weight:800">🎤 “내일 못 간다고 해 줘”</div>
 <div style="position:absolute;left:0;right:0;top:560px;text-align:center;font-size:60px;color:#C9B8A6">↓</div>
 <div style="position:absolute;left:30px;right:30px;bottom:${(2400 - kbTop) * 860 / 1080 + 24}px;border:3px solid #F8D978;border-radius:40px;padding:24px 34px;font-size:36px;font-weight:700;background:#FFFDF6">내일은 못 가요. 다음에 봐요.<span style="color:#432725">|</span></div>
 <div style="position:absolute;left:0;right:0;bottom:0;height:${(2400 - kbTop) * 860 / 1080}px;background-image:url(${kb});background-size:860px auto;background-position:0 -${kbTop * 860 / 1080}px"></div>
</div>`;
await snap(page('말하면, 다듬어서 입력칸에', '확인하고 보내기만 누르세요', `<div class="ph"><div class="scr">${chat}</div></div>`), 1080, 1920, '01.png');
await snap(page('말투는 골라 쓰세요', '정중하게 · 친구처럼 · 업무 연락 · 자녀에게 · 내 말투', phone(img(shots.home), 60)), 1080, 1920, '02.png');
// 3. 두벌식·천지인
const kbc = img(shots.cji), cTop = shots.cjiTop, H1 = (2400 - kbTop) * 860 / 1080, H2 = (2400 - cTop) * 860 / 1080;
await snap(page('두벌식도, 천지인도', '쓰던 자판 그대로 · 폴드에서도 알맞게', `<div class="ph"><div class="scr" style="background:#F8F7F3">
 <div style="position:absolute;left:0;right:0;top:40px;height:${H1}px;background-image:url(${kb});background-size:860px auto;background-position:0 -${kbTop * 860 / 1080}px"></div>
 <div style="position:absolute;left:0;right:0;top:${H1 + 80}px;height:${H2}px;background-image:url(${kbc});background-size:860px auto;background-position:0 -${cTop * 860 / 1080}px"></div>
</div></div>`), 1080, 1920, '03.png');
await snap(page('설치하고 두 단계면 끝', '키보드 켜기 · 마이크 허용 · 가입 없음', phone(img(shots.setup), 60)), 1080, 1920, '04.png');
await browser.close();
