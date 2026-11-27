// T-033 화면 확인·스크린샷 스크립트 (에이전트 탭 「이슈 목록」 창). Playwright 테스트 묶음(*.spec.ts)이 아니라 단독 실행용이다.
// 전제: 앱이 8778 에서 ISSUE_ENABLED=true 로 떠 있다(e2e/scripts/start-app.sh, 끝나면 stop-app.sh — 8777 은 건드리지 않는다).
//       playwright-core 는 e2e/node_modules 의 간접 의존, 브라우저는 시스템 Chrome(channel:'chrome').
// 실행: node e2e/tasks/T-033.check.mjs <스크린샷 출력 폴더>   예) node e2e/tasks/T-033.check.mjs docs/tasks/results/T-033
// 구성: A 실제 응답(토큰 없음·닫힌 창 요청 0) / B 목 응답 3크기 스크린샷 / C 동작(확인·실패·XSS) / D 재작업 R1~R4(새로고침·결과 줄·blocked 사유).
// 목 응답에서 /api/system/**·/api/usage 의 계정 경로는 <repo> 로 가린다(스크린샷 노출 방지).
import { createRequire } from 'node:module';
import fs from 'node:fs';
import os from 'node:os';
const require = createRequire(import.meta.url);
const { chromium, devices } = require('playwright-core');

const URL = 'http://127.0.0.1:8778/agent.html';
const OUT = process.argv[2];
fs.mkdirSync(OUT, { recursive: true });
const fails = [];
const check = (c, m) => { console.log((c ? 'PASS ' : 'FAIL ') + m); if (!c) fails.push(m); };

const mine = [
  { path: 'demo-user/shop-api', defaultBranch: 'main', private: true, archived: false, fork: false, updatedAt: '2026-11-26T10:00:00Z', webUrl: 'https://github.com/demo-user/shop-api' },
  { path: 'demo-user/web-client', defaultBranch: 'main', private: false, archived: false, fork: false, updatedAt: '2026-11-25T10:00:00Z', webUrl: 'https://github.com/demo-user/web-client' },
  { path: 'demo-user/docs-site-with-a-very-long-repository-name-that-wraps', defaultBranch: 'main', private: false, archived: true, fork: false, updatedAt: '2026-10-01T10:00:00Z', webUrl: 'https://github.com/demo-user/docs-site' },
  { path: 'demo-user/infra', defaultBranch: 'main', private: true, archived: false, fork: false, updatedAt: '2026-09-01T10:00:00Z', webUrl: 'https://github.com/demo-user/infra' },
];
const issuesBy = {
  'demo-user/shop-api': [
    { iid: 12, title: '주문 목록 API가 빈 페이지에서 500을 돌려줍니다 — 페이지 번호가 전체 쪽 수를 넘을 때 처리 필요', webUrl: 'https://github.com/demo-user/shop-api/issues/12', labels: ['ai-fix', 'bug'], author: 'demo-user', state: null, active: false },
    { iid: 9, title: '결제 취소 후 재고 복구 누락', webUrl: 'https://github.com/demo-user/shop-api/issues/9', labels: ['bug'], author: 'demo-user', state: 'REVIEW_WAIT', active: true, sessionId: 7 },
    { iid: 5, title: '외부 사용자가 올린 이슈', webUrl: 'https://github.com/demo-user/shop-api/issues/5', labels: ['ai-fix'], author: 'someone', state: null, active: false, blocked: '신뢰 작성자가 아니라 처리하지 않습니다' },
    { iid: 3, title: '중단된 이슈 <b>다시</b> 시작', webUrl: 'https://github.com/demo-user/shop-api/issues/3', labels: [], author: 'demo-user', state: 'FAILED', active: false, sessionId: 4 },
  ],
  'demo-user/web-client': [
    { iid: 31, title: '로그인 화면 포커스 순서', webUrl: 'https://github.com/demo-user/web-client/issues/31', labels: ['ai-fix'], author: 'demo-user', state: null, active: false },
    { iid: 30, title: '다크 모드 대비', webUrl: 'https://github.com/demo-user/web-client/issues/30', labels: ['ui'], author: 'demo-user', state: null, active: false },
  ],
  'demo-user/docs-site-with-a-very-long-repository-name-that-wraps': [
    { iid: 2, title: '오타 수정', webUrl: 'https://github.com/demo-user/docs-site/issues/2', labels: [], author: 'demo-user', state: null, active: false },
  ],
};

async function mockApi(ctx, st) {
  await ctx.route('**/api/**', async route => {
    const u = new URL_(route.request().url());
    const p = u.pathname;
    const json = (o, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(o) });
    if (p === '/api/issues-repos') {
      if (u.searchParams.get('refresh') && st.slowRefresh) await new Promise(r => setTimeout(r, st.slowRefresh));
      return json({ ok: true, repos: mine, truncated: false, max: 50 });
    }
    if (false) return json({ ok: true, repos: mine, truncated: false, max: 50 });
    if (p === '/api/issues-projects' && route.request().method() === 'GET')
      return json({ enabled: true, projects: st.targets.map(t => ({ path: t })) });
    if (p === '/api/issues-list') {
      const project = u.searchParams.get('project');
      const target = st.targets.some(t => t.toLowerCase() === project.toLowerCase());
      const m = mine.find(r => r.path === project);
      if (!target && !m) return json({ ok: false, result: '조회할 수 없는 저장소입니다.' });
      return json({ ok: true, project, label: 'ai-fix', target, archived: !!(m && m.archived), issues: issuesBy[project] || [] });
    }
    if (p === '/api/issues-start') {
      st.starts.push(route.request().postData());
      if (st.addedFail) { st.addedFail = false; return json({ ok: false, result: '시작하지 못했습니다. 대상에는 추가했습니다.', added: true }); }
      if (st.failNext) { st.failNext = false; return json({ ok: false, result: '이미 진행 중인 이슈입니다.', added: false }, 409); }
      const body = new URLSearchParams(route.request().postData());
      const added = !st.targets.includes(body.get('project'));
      if (added) st.targets.push(body.get('project'));
      return json({ ok: true, result: `${body.get('project')}#${body.get('number')} 큐에 넣었습니다.\n분석을 시작합니다.`, added, ref: body.get('project') + '#' + body.get('number') });
    }
    if (p.startsWith('/api/system/') || p === '/api/usage') {
      const r = await route.fetch();
      const t = (await r.text()).replace(/"[A-Za-z]:[^"]*Users[^"]*"/g, '"<repo>"');
      return route.fulfill({ response: r, body: t });
    }
    return route.continue();
  });
}
const URL_ = globalThis.URL;

async function newPage(browser, opts, mock) {
  const ctx = await browser.newContext(opts);
  const st = { targets: ['demo-user/shop-api', 'demo-user/legacy-tool'], starts: [], failNext: false, addedFail: false, slowRefresh: 0 };
  if (mock) await mockApi(ctx, st);
  const page = await ctx.newPage();
  const errors = [];
  page.on('console', m => { if (m.type() === 'error' && !(m.location().url || '').includes('favicon')) errors.push(m.text() + ' @ ' + m.location().url); });
  page.on('pageerror', e => errors.push('pageerror ' + e.message));
  const reqs = [];
  page.on('request', r => { if (/\/api\/issues-(list|repos|start)/.test(r.url())) reqs.push(r.method() + ' ' + r.url()); });
  return { ctx, page, st, errors, reqs };
}

async function overflow(page, label) {
  const o = await page.evaluate(() => {
    const d = document.getElementById('ilDlg');
    return { doc: document.documentElement.scrollWidth - window.innerWidth, dlg: d.open ? d.scrollWidth - d.clientWidth : 0,
      cols: [...d.querySelectorAll('.ilx-repos,.ilist')].map(e => e.scrollWidth - e.clientWidth) };
  });
  check(o.doc <= 0 && o.dlg <= 0 && o.cols.every(x => x <= 0), `${label} 가로 넘침 없음 ${JSON.stringify(o)}`);
}
async function touch(page, label) {
  const bad = await page.evaluate(() => {
    const d = document.getElementById('ilDlg');
    return [...d.querySelectorAll('button,a,input')].filter(e => e.offsetParent !== null && !e.disabled)
      .map(e => ({ t: (e.id || e.className || e.tagName) + ':' + (e.textContent || '').trim().slice(0, 12), h: Math.round(e.getBoundingClientRect().height), w: Math.round(e.getBoundingClientRect().width) }))
      .filter(x => x.h < 44 && !x.t.startsWith('A'));
  });
  check(bad.length === 0, `${label} 터치 영역 44px 이상 ${JSON.stringify(bad)}`);
}
async function noLeak(page, label) {
  const txt = await page.evaluate(() => document.body.innerText + ' ' + [...document.querySelectorAll('[title]')].map(e => e.title).join(' '));
  // 계정 이름은 코드에 적지 않고 실행 시점에 읽는다(공개 저장소)
  const account = os.userInfo().username.toLowerCase();
  const low = txt.toLowerCase();
  check(!low.includes('users') && !(account && low.includes(account)), `${label} 화면에 계정·경로 문구 없음`);
}

const browser = await chromium.launch({ channel: 'chrome' });

// ---- A. 실제 응답(가로채기 없음): 토큰 없음 + 대상 qa-group/qa-sample
{
  const { ctx, page, errors, reqs } = await newPage(browser, { viewport: { width: 1440, height: 900 } }, false);
  await page.goto(URL); await page.waitForTimeout(1500);
  check(reqs.length === 0, `창이 닫힌 동안 이슈·저장소 API 요청 0 (${reqs.length})`);
  await page.click('#btnList');
  await page.waitForFunction(() => document.querySelector('#ilNote').textContent.length > 0, null, { timeout: 8000 });
  const note = await page.textContent('#ilNote');
  check(/GITHUB_TOKEN/.test(note), '실제 응답: 왼쪽 위 토큰 안내 ' + note);
  const row = await page.textContent('#ilRepos .ilx-repo');
  check(/★/.test(row) && /qa-group\/qa-sample/.test(row), '실제 응답: qa-group/qa-sample ★ ' + row.trim().replace(/\s+/g, ' '));
  await page.waitForTimeout(1200);
  const rightTxt = await page.textContent('#ilist');
  console.log('right:', rightTxt.trim().slice(0, 100));
  await page.click('#ilClose'); reqs.length = 0; await page.waitForTimeout(3500);
  check(reqs.length === 0, '창을 닫은 뒤 요청 0');
  check(errors.length === 0, '콘솔 오류 0 ' + JSON.stringify(errors));
  await ctx.close();
}

// ---- B. 목 응답: 스크린샷·동작
const sizes = [
  ['1440', { viewport: { width: 1440, height: 900 } }],
  ['768', (() => { const { defaultBrowserType, ...d } = devices['iPad Mini']; return d; })()],
  ['390', (() => { const { defaultBrowserType, ...d } = devices['iPhone 13']; return d; })()],
];
for (const [name, opts] of sizes) {
  const { ctx, page, errors } = await newPage(browser, opts, true);
  await page.goto(URL); await page.waitForTimeout(1200);
  await page.click('#btnList');
  await page.waitForSelector('#ilist .irow', { state: 'attached', timeout: 8000 });
  if (name === '390') {
    check(await page.evaluate(() => getComputedStyle(document.querySelector('.ilx-right')).display === 'none'), '390 1단계: 이슈 칸 숨김');
    await overflow(page, '390 1단계'); await touch(page, '390 1단계');
    await page.click('#ilRepos .ilx-repo >> nth=0');
    check(await page.evaluate(() => getComputedStyle(document.querySelector('.ilx-left')).display === 'none'), '390 2단계: 저장소 칸 숨김');
    await page.waitForSelector('#ilist .irow');
  }
  await page.waitForTimeout(300);
  await overflow(page, name + ' 이슈'); if (name === '390') await touch(page, '390 2단계');
  await noLeak(page, name);
  if (name === '390') {
    const why = await page.evaluate(() => { const w = document.querySelector('#ilist .why'); if (!w) return null; const r = w.getBoundingClientRect(); return { t: w.textContent, vis: r.width > 0 && r.height > 0, right: r.right <= window.innerWidth }; });
    check(why && /신뢰 작성자/.test(why.t) && why.vis && why.right, 'R4 390 blocked 사유 글이 보임 ' + JSON.stringify(why));
  }
  await page.screenshot({ path: `${OUT}/${name}.png`, fullPage: true });
  if (name === '390') { await page.click('#ilBack'); check(await page.evaluate(() => getComputedStyle(document.querySelector('.ilx-right')).display === 'none'), '390 ← 저장소로 돌아감'); }
  check(errors.length === 0, name + ' 콘솔 오류 0 ' + JSON.stringify(errors));
  await ctx.close();
}

// ---- C. 동작: 비대상 시작 확인·★, 실패, 보관, blocked, XSS
{
  const { ctx, page, st, errors } = await newPage(browser, { viewport: { width: 1440, height: 900 } }, true);
  await page.goto(URL); await page.waitForTimeout(1000);
  await page.click('#btnList'); await page.waitForSelector('#ilist .irow');
  check(await page.evaluate(() => document.querySelectorAll('#ilist b').length === 0), '이슈 제목 HTML 이스케이프');
  check(await page.locator('#ilist .irow', { hasText: '#5' }).locator('button').count() === 0, 'blocked 이슈: 시작 버튼 없음');
  check(await page.locator('#ilist .irow', { hasText: '처리 안 함' }).count() === 1, 'blocked 배지');
  const names = await page.$$eval('#ilRepos .ilx-repo .nm', els => els.map(e => e.textContent));
  check(names[0] === 'shop-api' && names[1] === 'demo-user/legacy-tool', '대상 먼저(내 저장소는 repo만, 목록 밖 대상은 전체 경로) ' + names.join(','));
  // 필터
  await page.fill('#ilFilter', 'web'); check((await page.$$('#ilRepos .ilx-repo')).length === 1, '필터');
  await page.fill('#ilFilter', '');
  // 보관 저장소
  await page.click('#ilRepos .ilx-repo:has-text("docs-site")'); await page.waitForSelector('#ilist .irow');
  check(await page.locator('#ilist .irow button[disabled]').count() === 1, '보관 저장소 시작 버튼 비활성');
  // 비대상 시작
  await page.click('#ilRepos .ilx-repo:has-text("web-client")'); await page.waitForSelector('#ilist .irow');
  check(/대상 아님/.test(await page.textContent('#ilStatus')), '비대상 상태 한 줄');
  let msg = ''; page.once('dialog', async d => { msg = d.message(); await d.dismiss(); });
  await page.click('#ilist .irow >> nth=0 >> button');
  await page.waitForTimeout(300);
  check(/대상 프로젝트에 추가하고 #31 을 시작합니다.*지금 목록에 1건/.test(msg), '시작 확인 문구 ' + msg);
  check(st.starts.length === 0, '확인 취소 시 POST 없음');
  page.once('dialog', d => d.accept());
  await page.click('#ilist .irow >> nth=0 >> button');
  await page.waitForTimeout(500);
  check(st.starts.length === 1 && /project=demo-user%2Fweb-client&number=31/.test(st.starts[0]), 'POST issues-start 본문 ' + st.starts[0]);
  check(/큐에 넣었습니다/.test(await page.textContent('body')) , '성공 토스트');
  check(await page.locator('#ilRepos .ilx-repo:has-text("web-client") .star').textContent() === '★', 'added → ★');
  // 실패(409): ★ 없음
  await page.waitForTimeout(900);
  await page.click('#ilRepos .ilx-repo:has-text("infra")'); await page.waitForSelector('#ilist');
  st.failNext = true;
  await page.click('#ilRepos .ilx-repo:has-text("shop-api")'); await page.waitForSelector('#ilist .irow');
  page.once('dialog', d => d.accept());
  await page.click('#ilist .irow:has-text("#3") button');
  await page.waitForTimeout(500);
  check(await page.locator('.toast.err, .toast .err, [class*="toast"]').filter({ hasText: '이미 진행 중' }).count() >= 1, '실패 err 토스트');
  check(errors.filter(x => !/409/.test(x)).length === 0, '동작 콘솔 오류 0(의도한 가짜 409 로그 제외) ' + JSON.stringify(errors));
  await ctx.close();
}

// ---- D. 재작업 R1~R4
{
  const { ctx, page, st, errors } = await newPage(browser, { viewport: { width: 1440, height: 900 } }, true);
  await page.goto(URL); await page.waitForTimeout(1000);
  // R1: 새로고침 중 닫았다 다시 열면 「새로고침」 활성
  st.slowRefresh = 800;
  await page.click('#btnList'); await page.waitForSelector('#ilist .irow');
  await page.click('#ilReload');
  check(await page.locator('#ilReload').isDisabled(), 'R1 새로고침 중 비활성');
  await page.click('#ilClose'); await page.waitForTimeout(100);
  await page.click('#btnList'); await page.waitForSelector('#ilist .irow');
  check(await page.locator('#ilReload').isEnabled(), 'R1 닫았다 다시 열면 새로고침 활성');
  await page.waitForTimeout(1000);
  check(await page.locator('#ilReload').isEnabled(), 'R1 늦은 응답 뒤에도 활성');
  // R2: 새로고침 중 저장소 클릭해도 새로고침 결과 반영, 오류 문구 없음
  st.slowRefresh = 700;
  await page.click('#ilReload');
  await page.click('#ilRepos .ilx-repo:has-text("web-client")'); await page.waitForSelector('#ilist .irow');
  await page.waitForTimeout(1000);
  check(await page.locator('#ilReload').isEnabled(), 'R2 저장소 클릭 뒤에도 새로고침이 끝나 활성');
  const note = await page.textContent('#ilNote');
  check(!/불러오지 못했습니다/.test(note), 'R2 새로고침 오류 문구 없음 [' + note + ']');
  check(!/목록을 가져오지 못했습니다/.test(await page.textContent('#ilist')), 'R2 이슈 칸 오류 문구 없음');
  st.slowRefresh = 0;
  // R3: 시작 실패(가짜 409)·added:true+ok:false 결과가 창 안 줄에
  await page.click('#ilRepos .ilx-repo:has-text("shop-api")'); await page.waitForSelector('#ilist .irow');
  st.failNext = true; page.once('dialog', d => d.accept());
  await page.click('#ilist .irow:has-text("#3") button'); await page.waitForTimeout(1200);
  let m = await page.evaluate(() => { const e = document.getElementById('ilMsg'); return { t: e.textContent, hidden: e.hidden, err: e.classList.contains('err') }; });
  check(!m.hidden && m.err && /이미 진행 중/.test(m.t), 'R3 409 결과가 창 안 줄에(오류 색) ' + JSON.stringify(m));
  await page.click('#ilRepos .ilx-repo:has-text("web-client")'); await page.waitForSelector('#ilist .irow');
  check(await page.evaluate(() => document.getElementById('ilMsg').hidden), 'R3 저장소 변경 시 줄 지움');
  st.addedFail = true; page.once('dialog', d => d.accept());
  await page.click('#ilist .irow >> nth=1 >> button'); await page.waitForTimeout(1200);
  m = await page.evaluate(() => { const e = document.getElementById('ilMsg'); return { t: e.textContent, hidden: e.hidden, err: e.classList.contains('err') }; });
  check(!m.hidden && m.err && /대상에는 추가했습니다/.test(m.t), 'R3 added:true+ok:false 결과 줄(700ms 갱신 뒤에도 유지) ' + JSON.stringify(m));
  check(errors.filter(x => !/409/.test(x)).length === 0, 'D 콘솔 오류 0 ' + JSON.stringify(errors));
  await ctx.close();
}
await browser.close();
console.log(fails.length ? 'FAILS: ' + fails.length : 'ALL PASS');
