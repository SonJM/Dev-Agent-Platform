// 홈 · 메신저 · 플랫폼 화면 공통 스크립트. 각 화면이 필요한 load*() 만 골라 부른다.
const { $, esc, fmt, ago, num, KOR, cls, isActive, I, toast } = UI;
/** 요소가 이 화면에 있을 때만 이벤트를 건다(홈·메신저·플랫폼이 이 파일을 함께 쓴다). */
function on(id, ev, fn) { const e = $(id); if (e) e.addEventListener(ev, fn); }

for (const [id, ic] of [['icBell','bell'],['icSearch','search'],['icMcp','pulse'],['icPlat','branch'],['icSys','pulse'],['icSkill','book'],['icAgent','agent'],['icArrow','arrow'],['icTerm','terminal'],['icPlus','plus']]) { const e=$(id); if (e) e.innerHTML = I[ic]; }

// ---------- 메신저 연결 상태(GET /api/platform 의 messenger 포트) ----------
async function loadMessenger() {
  const el = $('msgrState'); if (!el) return;
  let d; try { d = await (await fetch('/api/platform')).json(); } catch (e) { UI.set(el, '<div class="md-empty">상태 조회 실패</div>'); return; }
  const x = (d.ports || []).find(p => p.port === 'messenger');
  if (!x) { UI.set(el, '<div class="md-empty">메신저 포트 정보가 없습니다.</div>'); return; }
  const b = PLAT_BADGE[x.status] || ['gray', x.status];
  $('msgrSummary').textContent = b[1];
  UI.set(el, `<div class="msgr"><div class="r1"><span class="badge ${b[0]}">${esc(b[1])}</span><b>${esc(x.label)}</b><span class="prov" title="${esc(x.envKey)}=${esc(x.provider)}">${esc(x.provider)}</span></div>
    <div class="sum">${esc(x.summary)}</div></div>`
    + (x.provider === 'none' ? `<div class="callout info" style="margin-top:10px"><span><b>메신저 없이 사용 중</b> — 알림은 알림함에 남습니다. 메신저를 붙이려면 <code>.env</code> 의 <code>PLATFORM_MESSENGER</code> 를 바꾸고 재시작하세요.</span></div>` : ''));
}

// ---------- 메신저 알림(유형별 켜고 끄기) ----------
async function loadNotify() {
  const el = $('notifyKinds'); if (!el) return;
  let d; try { d = await (await fetch('/api/messenger-notify')).json(); } catch (e) { return; }
  UI.set(el, (d.connected ? '' : '<div class="callout info" style="margin-bottom:10px"><span>메신저가 연결돼 있지 않아 지금은 어떤 알림도 보내지 않습니다. 설정은 연결 뒤에 적용됩니다.</span></div>')
    + d.kinds.map(k => `<label class="nrow"><span class="nl"><b>${esc(k.label)}</b><span class="hint">${esc(k.hint)}</span></span>
      <span class="sw"><input type="checkbox" data-notify="${esc(k.key)}" ${k.on ? 'checked' : ''}><i></i></span></label>`).join(''));
  const n = d.kinds.filter(k => k.on).length;
  $('notifyCount').textContent = n === d.kinds.length ? '모두 켜짐' : n === 0 ? '모두 꺼짐' : n + '/' + d.kinds.length + ' 켜짐';
}
on('notifyKinds', 'change', async e => {
  const t = e.target; if (!t.dataset || !t.dataset.notify) return;
  try {
    const r = await (await fetch('/api/messenger-notify?key=' + encodeURIComponent(t.dataset.notify) + '&on=' + t.checked, { method: 'POST' })).json();
    toast(r.result, r.ok ? 'ok' : 'err');
  } catch (err) { toast('저장 실패', 'err'); }
  loadNotify();
});

// ---------------------------------------------------------------- 사용자 정의 MCP 서버 (등록·수정·삭제)
let mcpCustomList = [];
async function loadMcpCustom() {
  let d; try { d = await (await fetch('/api/mcp-servers')).json(); } catch (e) { $('mcpCustom').innerHTML = '<div class="hint">조회 실패</div>'; return; }
  mcpCustomList = d.servers || [];
  $('mcpCustom').innerHTML = mcpCustomList.map(x => `<div class="it ${x.enabled ? '' : 'off'}">
      <div class="r1"><span class="badge ${x.enabled ? 'info' : 'gray'}">${esc(x.type)}</span><b>${esc(x.name)}</b>${x.enabled ? '' : '<span class="badge gray">사용 안 함</span>'}
        ${(x.missingEnv || []).length ? `<span class="badge err" title=".env 에 값이 없습니다">env 없음: ${esc(x.missingEnv.join(', '))}</span>` : ''}
        <span style="flex:1"></span><button class="btn sm" onclick="mcpEdit('${esc(x.name)}')">수정</button><button class="btn sm danger" onclick="mcpDelete('${esc(x.name)}')">삭제</button></div>
      ${x.description ? `<div class="d">${esc(x.description)}</div>` : ''}
      <div class="m">${esc(x.type === 'stdio' ? (x.command || '') + ' ' + (x.args || '').split(/\n/).join(' ') : (x.url || ''))} · 허용 ${esc((x.allowedTools || []).join(', '))}</div>
    </div>`).join('') || '<div class="hint">등록된 MCP 서버가 없습니다. + 서버 추가로 올리면 이슈 에이전트와 리서치가 조회 도구로 씁니다.</div>';
}
function mcpTypeChanged() { const st = $('mfType').value === 'stdio'; $('mfRemote').style.display = st ? 'none' : ''; $('mfLocal').style.display = st ? '' : 'none'; }
function mcpEdit(name) {
  const x = mcpCustomList.find(v => v.name === name) || { type: 'http', enabled: true };
  $('mfName').value = x.name || ''; $('mfName').disabled = !!x.name; $('mfType').value = x.type || 'http';
  $('mfUrl').value = x.url || ''; $('mfHeaders').value = x.headers || ''; $('mfCommand').value = x.command || ''; $('mfArgs').value = x.args || '';
  $('mfTools').value = x.tools || ''; $('mfDesc').value = x.description || ''; $('mfEnabled').checked = x.enabled !== false;
  mcpTypeChanged(); $('mcpForm').classList.add('open'); (x.name ? $('mfUrl') : $('mfName')).focus();
}
function mcpFormClose() { $('mcpForm').classList.remove('open'); }
async function mcpSave() {
  const body = new URLSearchParams({ name: $('mfName').value.trim(), type: $('mfType').value, url: $('mfUrl').value, headers: $('mfHeaders').value,
    command: $('mfCommand').value, args: $('mfArgs').value, tools: $('mfTools').value, description: $('mfDesc').value, enabled: $('mfEnabled').checked });
  try {
    const r = await (await fetch('/api/mcp-servers', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body })).json();
    toast(r.result, r.ok ? 'ok' : 'err'); if (r.ok) { mcpFormClose(); loadMcpCustom(); loadPlatform(); }
  } catch (e) { toast('저장 실패', 'err'); }
}
async function mcpDelete(name) {
  if (!confirm(`MCP 서버 '${name}' 를 삭제할까요?`)) return;
  try { const r = await (await fetch('/api/mcp-servers/delete?name=' + encodeURIComponent(name), { method: 'POST' })).json(); toast(r.result, r.ok ? 'ok' : 'err'); loadMcpCustom(); loadPlatform(); }
  catch (e) { toast('삭제 실패', 'err'); }
}

// ---------------------------------------------------------------- 스킬 (등록·수정·삭제)
const STAGE_KO2 = { analyze: '분석·설계', fix: '코드 수정', verify: '검증', research: '리서치', maintain: '플랫폼 관리' };
let skillList = [], skillStages = ['analyze', 'fix', 'verify', 'research', 'maintain'];
async function loadSkills() {
  let d; try { d = await (await fetch('/api/skills')).json(); } catch (e) { $('skillList').innerHTML = '<div class="hint">조회 실패</div>'; return; }
  skillList = d.skills || []; skillStages = d.stages || skillStages;
  $('skillList').innerHTML = skillList.map(x => `<div class="it ${x.enabled ? '' : 'off'}">
      <div class="r1"><b>${esc(x.name)}</b>${x.stages.length === skillStages.length ? '<span class="badge gray">전 단계</span>' : x.stages.map(k => `<span class="badge info">${STAGE_KO2[k] || k}</span>`).join('')}
        ${x.enabled ? '' : '<span class="badge gray">사용 안 함</span>'}<span class="hint">${x.chars.toLocaleString()}자${x.chars > 1500 ? ' · 파일 참조' : ''}</span>
        <span style="flex:1"></span><button class="btn sm" onclick="skillEdit('${esc(x.name)}')">수정</button><button class="btn sm danger" onclick="skillDelete('${esc(x.name)}')">삭제</button></div>
      <div class="d">${esc(x.description)}</div></div>`).join('') || '<div class="hint">등록된 스킬이 없습니다. 팀 코딩 규칙, 커밋 규칙, 조사 문서 양식 같은 반복 지침을 올려 두면 에이전트가 따릅니다.</div>';
}
function skillEdit(name) {
  const x = skillList.find(v => v.name === name) || { stages: [], enabled: true };
  $('sfName').value = x.name || ''; $('sfName').disabled = !!x.name; $('sfDesc').value = x.description || ''; $('sfBody').value = x.body || '';
  const all = !x.name || x.stages.length === skillStages.length;
  $('sfStages').innerHTML = skillStages.map(k => `<label><input type="checkbox" value="${k}" ${!all && x.stages.includes(k) ? 'checked' : ''}> ${STAGE_KO2[k] || k}</label>`).join('');
  $('sfEnabled').checked = x.enabled !== false; skillChars();
  $('skillForm').classList.add('open'); (x.name ? $('sfBody') : $('sfName')).focus();
}
function skillChars() { const n = $('sfBody').value.length; $('sfChars').textContent = n.toLocaleString() + '자' + (n > 1500 ? ' · 길어서 파일 참조로 실립니다' : ''); }
on('sfBody', 'input', skillChars);
function skillFormClose() { $('skillForm').classList.remove('open'); }
async function skillSave() {
  const stages = [...$('sfStages').querySelectorAll('input:checked')].map(i => i.value).join(',');
  const body = new URLSearchParams({ name: $('sfName').value.trim(), description: $('sfDesc').value, body: $('sfBody').value, stages, enabled: $('sfEnabled').checked });
  try {
    const r = await (await fetch('/api/skills', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body })).json();
    toast(r.result, r.ok ? 'ok' : 'err'); if (r.ok) { skillFormClose(); loadSkills(); }
  } catch (e) { toast('저장 실패', 'err'); }
}
async function skillDefaults() {
  try { const r = await (await fetch('/api/skills/defaults', { method: 'POST' })).json(); toast(r.result, r.ok ? 'ok' : 'err'); loadSkills(); }
  catch (e) { toast('기본 스킬 넣기 실패', 'err'); }
}
async function skillDelete(name) {
  if (!confirm(`스킬 '${name}' 를 삭제할까요?`)) return;
  try { const r = await (await fetch('/api/skills/delete?name=' + encodeURIComponent(name), { method: 'POST' })).json(); toast(r.result, r.ok ? 'ok' : 'err'); loadSkills(); }
  catch (e) { toast('삭제 실패', 'err'); }
}

// ---------------------------------------------------------------- 연동(포트 구현체 상태 · 연결 테스트)
const PLAT_BADGE = { ok: ['ok', '정상'], warn: ['wait', '주의'], down: ['err', '실패'] };
const PLAT_KEYS = { host: '호스트', account: '계정', userSeq: '사용자', model: '모델', effort: '노력',
  cli: 'CLI', running: '실행 중', sections: '섹션', lastIngest: '마지막 적재', source: '소스', models: '단계별 모델', mcpServers: 'MCP 서버', lastOkAt: '마지막 성공', lastElapsedMs: '소요', lastErrorAt: '마지막 실패' };
const platResults = {};
function platVal(k, v) {
  if (k === 'lastOkAt' || k === 'lastErrorAt') return UI.ago(v);
  if (k === 'lastElapsedMs') return v >= 1000 ? (v / 1000).toFixed(1) + 's' : v + 'ms';
  if (k === 'cli') return String(v).split(/[\\/]/).pop();
  if (v === true) return '예'; if (v === false) return '아니오';
  return String(v);
}
async function loadPlatform() {
  let d; try { d = await (await fetch('/api/platform')).json(); } catch (e) { $('platPorts').innerHTML = '<div class="md-empty">상태 조회 실패</div>'; return; }
  const ports = d.ports || [];
  const bad = ports.filter(x => x.status !== 'ok').length;
  $('platSummary').textContent = bad ? `${ports.length - bad}/${ports.length} 정상` : `${ports.length}개 모두 정상`;
  $('platPorts').innerHTML = ports.map(x => {
    const b = PLAT_BADGE[x.status] || ['gray', x.status];
    const det = Object.entries(x.details || {}).filter(([k]) => PLAT_KEYS[k]).map(([k, v]) => `<span><b>${PLAT_KEYS[k]}</b> ${esc(platVal(k, v))}</span>`).join('');
    const r = platResults[x.port];
    return `<div class="plat">
      <div class="r1"><span class="badge ${b[0]}">${esc(b[1])}</span><b>${esc(x.label)}</b><span class="prov" title="${esc(x.envKey)}=${esc(x.provider)}">${esc(x.provider)}</span><span class="sp" style="flex:1"></span>
        <button class="btn sm" id="platBtn-${x.port}" onclick="platTest('${x.port}')" title="${esc(x.testHint)}">테스트</button></div>
      <div class="sum" title="${esc(x.role)}">${esc(x.summary)} <span style="color:var(--muted)">· ${esc(x.role)}</span></div>
      ${det ? `<div class="det">${det}</div>` : ''}
      ${r ? `<div class="res ${r.ok ? 'ok' : 'err'}">${r.ok ? '✓' : '✖'} ${esc(r.message)} · ${r.elapsedMs}ms</div>` : ''}
    </div>`;
  }).join('');
}
async function platTest(port) {
  const btn = $('platBtn-' + port); if (btn) { btn.disabled = true; btn.textContent = '확인 중…'; }
  try {
    const r = await (await fetch('/api/platform/test?port=' + port, { method: 'POST' })).json();
    platResults[port] = r; toast((r.ok ? '연결 정상: ' : '연결 실패: ') + r.message, r.ok ? 'ok' : 'err');
  } catch (e) { platResults[port] = { ok: false, message: String(e), elapsedMs: 0 }; toast('테스트 실패', 'err'); }
  loadPlatform();
}

// ---------------------------------------------------------------- 시스템 · 설치 점검 · 구성 묶음
const RUN_MODE = { systemd: '서비스(systemd)', direct: '직접 실행' };
const DOC_BADGE = { ok: ['ok', '정상'], warn: ['wait', '주의'], fail: ['err', '필요'], off: ['gray', '끔'] };
function upFmt(sec) { return sec < 3600 ? Math.floor(sec / 60) + '분' : sec < 86400 ? Math.floor(sec / 3600) + '시간' : Math.floor(sec / 86400) + '일'; }
async function loadSystem() {
  let s; try { s = await (await fetch('/api/system/status')).json(); } catch (e) { return; }
  if ($('sysInfo')) {
    $('sysInfo').innerHTML = `<span><b>버전</b>${esc(s.version)}</span><span><b>실행</b>${esc(RUN_MODE[s.runMode] || s.runMode)}</span><span><b>OS</b>${esc(s.os)}</span>`
      + `<span><b>DB</b>${s.db === 'embedded' ? '내장' : '외부'}</span><span><b>가동</b>${upFmt(s.uptimeSec)}</span><span title="${esc(s.home)}"><b>홈</b>${esc(s.home.length > 34 ? '…' + s.home.slice(-33) : s.home)}</span>`;
    const rb = $('sysRestartBtn'); rb.style.display = s.supervised ? '' : 'none';
    rb.title = s.busy ? '작업 중인 세션 ' + s.busy + '건 — 끝난 뒤 재시작하세요' : '.env 를 고쳤거나 새 버전을 올렸을 때';
  }
  const banner = $('setupBanner');
  if (banner) {
    const need = s.setup || [];
    banner.style.display = need.length ? '' : 'none';
    if (need.length) banner.innerHTML = `${I.warn}<span>아직 설정하지 않은 항목이 있습니다: <b>${need.map(esc).join(' · ')}</b></span><a class="btn sm" href="/platform.html">설치 점검 열기</a>`;
  }
  return s;
}
async function loadDoctor(refresh) {
  const btn = $('doctorBtn'); if (btn) { btn.disabled = true; btn.textContent = '점검 중…'; }
  let d; try { d = await (await fetch('/api/system/doctor?refresh=' + (refresh ? 'true' : 'false'))).json(); } catch (e) { d = null; }
  if (btn) { btn.disabled = false; btn.textContent = '다시 점검'; }
  if (!d) { $('doctorList').innerHTML = '<div class="md-empty">점검 실패</div>'; return; }
  $('sysSummary').textContent = d.fail ? `필요 ${d.fail}건` + (d.warn ? ` · 주의 ${d.warn}건` : '') : d.warn ? `주의 ${d.warn}건` : '모두 정상';
  $('doctorList').innerHTML = (d.checks || []).map(c => {
    const b = DOC_BADGE[c.status] || ['gray', c.status];
    // 정상·끔 항목은 한 줄로 접고, 손봐야 할 항목만 설명과 고치는 방법을 펼친다
    if (!c.hint) return `<div class="doc"><div class="r1"><span class="badge ${b[0]}">${esc(b[1])}</span><b>${esc(c.name)}</b><span class="dd" title="${esc(c.detail || '')}">${esc(c.detail || '')}</span></div></div>`;
    return `<div class="doc"><div class="r1"><span class="badge ${b[0]}">${esc(b[1])}</span><b>${esc(c.name)}</b></div>
      <div class="d">${esc(c.detail || '')}</div><div class="h">→ ${esc(c.hint)}</div></div>`;
  }).join('');
  $('envHint').innerHTML = '설정 파일: <code>' + esc(d.envFile) + '</code> — 고친 뒤 재시작하면 반영됩니다. 계정·토큰은 화면에서 다루지 않습니다.';
  if (refresh) toast('설치 점검 완료', d.fail ? 'err' : 'ok');
}
async function sysRestart() {
  let s; try { s = await (await fetch('/api/system/status')).json(); } catch (e) { return; }
  let force = false;
  if (s.busy) {
    if (!confirm('작업 중인 세션이 ' + s.busy + '건 있습니다. 지금 재시작하면 그 작업은 실패 처리됩니다. 그래도 재시작할까요?')) return;
    force = true;
  } else if (!confirm('Dev Agent 를 재시작할까요? 10초쯤 걸립니다.')) return;
  let r; try { r = await (await fetch('/api/system/restart?force=' + force, { method: 'POST' })).json(); } catch (e) { toast('요청 실패', 'err'); return; }
  toast(r.result, r.ok ? 'ok' : 'err');
  if (!r.ok) return;
  const t0 = Date.now();
  const wait = setInterval(async () => {
    try { const x = await (await fetch('/api/system/status')).json(); if (x.uptimeSec < 60 && Date.now() - t0 > 3000) { clearInterval(wait); window.top.location.reload(); } } catch (e) { /* 내려가 있는 동안 */ }
    if (Date.now() - t0 > 120000) { clearInterval(wait); toast('다시 연결되지 않습니다. 로그를 확인하세요.', 'err'); }
  }, 1500);
}
async function bundleImport(input) {
  const f = input.files && input.files[0]; input.value = '';
  if (!f) return;
  let json; try { json = JSON.parse(await f.text()); } catch (e) { toast('JSON 파일을 읽지 못했습니다', 'err'); return; }
  const n = k => (json[k] || []).length;
  if (!confirm(`구성 묶음을 가져올까요?\n스킬 ${n('skills')} · MCP 서버 ${n('mcpServers')} · 루틴 ${n('routines')}${json.settings ? ' · 에이전트 설정' : ''}\n같은 이름은 덮어씁니다.`)) return;
  let r; try { r = await (await fetch('/api/bundle/import', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(json) })).json(); }
  catch (e) { toast('가져오기 실패', 'err'); return; }
  toast(r.result + ((r.errors || []).length ? ' — ' + r.errors[0] : ''), r.ok ? 'ok' : 'err');
  if (typeof loadSkills === 'function' && $('skillList')) { loadSkills(); loadMcpCustom(); loadModels(); loadProjects(); }
}

// ---------------------------------------------------------------- issue agent
const ORDER = {NEW:0, PREPARING:1, ANALYZING:1, READY:2, DESIGN_WAIT:2, FIXING:3, VERIFYING:4, REVIEW_WAIT:5, PUSHING:6, DONE:7};
let ACTIVE_ID = null;
async function loadIssues() {
  let d; try { d = await (await fetch('/api/agent/overview?limit=40')).json(); } catch(e) { UI.setAgentChip(null); return; }
  UI.setAgentChip(d);
  window.LAST_OVERVIEW = { at: Date.now(), data: d };   // 홈 화면이 같은 응답을 다시 쓴다
  const act = $('issueActive');
  if (!d.enabled) { act.innerHTML = `<div class="callout err"><span class="ic">${I.warn}</span><span>이슈 에이전트가 비활성 상태입니다 (GITLAB_ENABLED=false).</span></div>`; $('issueRecent').innerHTML=''; return; }
  const activeIds = d.activeIds || (d.activeId != null ? [d.activeId] : []);
  const busyIds = d.busyIds || [];
  const actives = activeIds.map(id => (d.sessions||[]).find(x => x.id === id)).filter(Boolean);
  ACTIVE_ID = d.activeId;
  const sig = actives.length ? actives.map(s => `${s.id}|${s.state}|${busyIds.includes(s.id)}|${s.feedbackRounds}`).join(';') : 'idle|'+(d.nextRef||'');
  const typing = [...document.querySelectorAll('.issueReplyIn')].some(i => document.activeElement === i || i.value);
  if (sig === act.dataset.sig && typing) { /* 피드백 입력 중 — 다시 그리지 않음 */ }
  else if (sig === act.dataset.sig && actives.length) { actives.forEach(s => { const w = act.querySelector(`.active[data-id="${s.id}"] .dots span`); if (w) w.textContent = ago(s.updatedAt); }); }
  else if (!actives.length) {
    act.dataset.sig = sig;
    act.innerHTML = `<div class="active"><div class="in"><div class="ref"><span class="badge gray">유휴</span><span>진행 중인 이슈 없음${d.nextRef?' · 다음 대기 '+esc(d.nextRef):''}</span></div>
      <div class="hint" style="margin-top:8px">라벨 <code>ai-fix</code> 이슈가 등록되면 자동으로 시작합니다(동시 ${d.concurrency||1}건). 아래 입력으로 수동 시작도 됩니다.</div></div></div>`;
  } else {
    act.dataset.sig = sig;
    act.innerHTML = actives.map(s => {
      const waiting = s.state==='DESIGN_WAIT'||s.state==='REVIEW_WAIT'; const cur = ORDER[s.state]; const busy = busyIds.includes(s.id);
      const dots = ['ANALYZING','DESIGN_WAIT','FIXING','VERIFYING','REVIEW_WAIT','PUSHING','DONE'].map(k => { const o=ORDER[k]; return `<i class="${o<cur?'d':o===cur?(waiting?'w':'c'):''}"></i>`; }).join('');
      return `<div class="active ${waiting?'wait':'run'}" data-id="${s.id}"><div class="in">
      <div class="ref"><span class="badge ${cls(s.state)}">${busy?'작업 중 · ':''}${KOR[s.state]}</span><span>${esc(s.projectPath)}#${s.issueIid}</span>${s.feedbackRounds?`<span style="color:var(--muted)">피드백 ${s.feedbackRounds}</span>`:''}</div>
      <div class="ttl">${esc(s.issueTitle)}</div>
      <div class="dots">${dots}<span>${ago(s.updatedAt)}</span></div>
      <div class="row" style="flex-wrap:wrap">
        <a class="btn primary" href="/agent.html#s=${s.id}">${waiting ? (s.state==='DESIGN_WAIT'?'설계안 검토하기':'변경 내용 검토하기') : '실시간 활동 보기'} ${I.arrow}</a>
        ${waiting ? `<button class="btn" onclick="issueReply('진행', ${s.id})" ${busy?'disabled':''}>${I.check}진행</button>` : ''}<button class="btn danger" onclick="issueAbort(${s.id})" ${busy&&waiting?'disabled':''}>${I.x}중단</button>
      </div>
      ${waiting ? `<div class="row" style="margin-top:10px"><div class="field"><input class="issueReplyIn" data-id="${s.id}" placeholder="피드백 문장 (Enter)" autocomplete="off"></div><button class="btn" onclick="issueReply(null, ${s.id})">${I.send}보내기</button></div>` : ''}
    </div></div>`; }).join('') + (actives.length > 1 ? `<div class="hint" style="margin-top:6px">동시 ${actives.length}/${d.concurrency||actives.length}건 진행 중. 자기방에서 답할 땐 「repo#1 진행」처럼 대상을 앞에 붙입니다.</div>` : '');
    document.querySelectorAll('.issueReplyIn').forEach(inp => inp.addEventListener('keydown', e => { if (e.key==='Enter') issueReply(null, Number(inp.dataset.id)); }));
  }
  const list = (d.sessions||[]).filter(x => !activeIds.includes(x.id)).slice(0,6);
  $('issueCount').textContent = (d.sessions||[]).length;
  $('issueRecent').innerHTML = list.map(x => `<a class="sess" href="/agent.html#s=${x.id}"><span class="badge ${cls(x.state)}">${KOR[x.state]||x.state}</span><span class="ref">${esc(x.projectPath.split('/').pop())}#${x.issueIid}</span><span class="ttl" title="${esc(x.issueTitle)}">${esc(x.issueTitle)}</span><span class="when">${ago(x.updatedAt)}</span></a>`).join('')
    || '<div class="md-empty">세션 이력이 없습니다.</div>';
}
async function issueReply(text, sessionId) {
  const inp = sessionId != null ? document.querySelector(`.issueReplyIn[data-id="${sessionId}"]`) : null;
  const t = text || (inp ? inp.value.trim() : ''); if (!t) return;
  if (t==='중단') return issueAbort(sessionId);
  document.querySelectorAll(`#issueActive .active[data-id="${sessionId}"] button`).forEach(b => b.disabled = true);   // 연타 방지
  let ok = false;
  try {
    const r = await (await fetch('/api/issues-reply', {method:'POST', headers:{'Content-Type':'application/x-www-form-urlencoded'},
      body: new URLSearchParams({text: t, sessionId: sessionId != null ? String(sessionId) : ''})})).json();
    ok = /전달했습니다/.test(r.result); toast(r.result, ok?'ok':'err');
  } catch(e) { toast('전송 실패', 'err'); }
  if (ok && inp) inp.value=''; setTimeout(loadIssues, 800);
}
async function issueAbort(sessionId) {
  if (!confirm('진행 중인 이슈를 중단할까요? 작업트리 변경은 버려지고 실행 중인 작업은 즉시 종료됩니다.')) return;
  const id = sessionId != null ? sessionId : ACTIVE_ID;
  if (id == null) { toast('중단할 세션이 없습니다', 'err'); return; }
  try { const r = await (await fetch(`/api/agent/sessions/${id}/abort`, {method:'POST'})).json(); toast(r.result); } catch(e) { toast('실행 실패', 'err'); }
  setTimeout(loadIssues, 800);
}
async function issueCommandText(t) {
  if (t==='중단') return issueAbort(null);
  try { const r = await (await fetch('/api/issues-command?cmd='+encodeURIComponent(t), {method:'POST'})).json(); toast((r.result||'').split('\n')[0]); } catch(e) { toast('실행 실패', 'err'); }
  setTimeout(loadIssues, 800);
}
async function issueCommand() { const t = $('issueCmd').value.trim(); if (!t) return; $('issueCmd').value=''; issueCommandText(t); }

// ---- 모델(개발/검증) — DB 저장, 다음 Claude 실행부터 적용
let MODEL_SIG = '';
async function loadModels() {
  let d; try { d = await (await fetch('/api/agent-models')).json(); } catch(e) { return; }
  const sig = JSON.stringify(d); if (sig === MODEL_SIG) return; MODEL_SIG = sig;
  const opt = (list, cur) => list.map(v => `<option value="${v}" ${v===cur?'selected':''}>${v}</option>`).join('');
  $('designModel').innerHTML = opt(d.models, d.designModel); $('designEffort').innerHTML = opt(d.efforts, d.designEffort);
  $('devModel').innerHTML = opt(d.models, d.devModel); $('devEffort').innerHTML = opt(d.efforts, d.devEffort);
  $('verifyModel').innerHTML = opt(d.models, d.verifyModel); $('verifyEffort').innerHTML = opt(d.efforts, d.verifyEffort);
  $('modelHint').textContent = `기획 ${d.designModel}/${d.designEffort} · 개발 ${d.devModel}/${d.devEffort} · 테스트 ${d.verifyModel}/${d.verifyEffort} · 사용 불가 시 ${d.fallback||'opus'} 대체`;
  const sc = $('subagentsChk'); if (sc) sc.checked = !!d.subagents;
  const tc = $('teamsChk'); if (tc) tc.checked = !!d.teams;
}
async function toggleTeams(on) {
  try { const r = await (await fetch('/api/agent-teams?on=' + on, {method:'POST'})).json(); toast(r.result, on ? 'warn' : 'ok'); } catch(e) { toast('저장 실패', 'err'); }
}
async function toggleSubagents(on) {
  try { const r = await (await fetch('/api/agent-subagents?on=' + on, {method:'POST'})).json(); toast(r.result, on ? 'warn' : 'ok'); } catch(e) { toast('저장 실패', 'err'); }
  MODEL_SIG = ''; loadModels();
}
async function saveModels() {
  $('modelSaveBtn').disabled = true;
  try {
    const body = new URLSearchParams({designModel:$('designModel').value, designEffort:$('designEffort').value,
      devModel:$('devModel').value, devEffort:$('devEffort').value, verifyModel:$('verifyModel').value, verifyEffort:$('verifyEffort').value});
    const r = await (await fetch('/api/agent-models', {method:'POST', headers:{'Content-Type':'application/x-www-form-urlencoded'}, body})).json();
    toast(r.result, /저장했습니다/.test(r.result)?'ok':'err');
  } catch(e) { toast('저장 실패', 'err'); }
  $('modelSaveBtn').disabled = false; MODEL_SIG = ''; loadModels();
}

// ---- 대상 프로젝트 추가/삭제 (DB 저장, 재시작 불필요)
async function loadProjects() {
  let d; try { d = await (await fetch('/api/issues-projects')).json(); } catch(e) { return; }
  const list = d.projects || [];
  $('projCount').textContent = list.length;
  $('projList').innerHTML = list.map(p => `<span class="chip ${p.error?'err':'ok'}" title="${esc(p.error || ('id '+p.id+' · '+(p.defaultBranch||'')))}">
      <span class="dot"></span>${p.webUrl ? `<a href="${esc(p.webUrl)}" target="_blank" rel="noopener" style="color:inherit;text-decoration:none">${esc(p.path)}</a>` : esc(p.path)}
      <button class="x" onclick="projectRemove('${esc(p.path)}')" title="목록에서 빼기">${I.x}</button></span>`).join('')
    || '<div class="md-empty">대상 프로젝트가 없습니다. 아래에서 추가하세요.</div>';
}
async function projectAdd() {
  const t = $('projIn').value.trim(); if (!t) return;
  $('projAddBtn').disabled = true;
  try { const r = await (await fetch('/api/issues-projects/add', {method:'POST', headers:{'Content-Type':'application/x-www-form-urlencoded'}, body:new URLSearchParams({path:t})})).json();
    const ok = /추가했습니다/.test(r.result); toast(r.result, ok?'ok':'err'); if (ok) $('projIn').value=''; }
  catch(e) { toast('추가 실패', 'err'); }
  $('projAddBtn').disabled = false; loadProjects();
}
async function projectRemove(path) {
  if (!confirm(path + ' 를 대상 프로젝트에서 뺄까요? (진행 중인 세션은 유지됩니다)')) return;
  try { const r = await (await fetch('/api/issues-projects/remove', {method:'POST', headers:{'Content-Type':'application/x-www-form-urlencoded'}, body:new URLSearchParams({path})})).json();
    toast(r.result, /뺐습니다/.test(r.result)?'ok':'err'); }
  catch(e) { toast('삭제 실패', 'err'); }
  loadProjects();
}
on('projIn', 'keydown', e => { if (e.key==='Enter') projectAdd(); });
on('issueCmd', 'keydown', e => { if(e.key==='Enter') issueCommand(); });
