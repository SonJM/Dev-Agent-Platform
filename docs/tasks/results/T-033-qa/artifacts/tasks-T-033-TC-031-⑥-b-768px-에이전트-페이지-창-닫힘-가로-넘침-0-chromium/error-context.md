# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: tasks\T-033.spec.ts >> TC-031 ⑥-b 768px 에이전트 페이지(창 닫힘) 가로 넘침 0
- Location: tasks\T-033.spec.ts:234:1

# Error details

```
Error: expect(received).toBeLessThanOrEqual(expected)

Expected: <= 0
Received:    131
```

# Page snapshot

```yaml
- generic [ref=e2]:
  - banner [ref=e3]:
    - link "Dev Agent" [ref=e4] [cursor=pointer]:
      - /url: /home.html
    - navigation [ref=e11]:
      - link "홈" [ref=e12] [cursor=pointer]:
        - /url: /home.html
      - link "에이전트" [ref=e16] [cursor=pointer]:
        - /url: /agent.html
      - link "리서치" [ref=e21] [cursor=pointer]:
        - /url: /research.html
      - link "메신저" [ref=e26] [cursor=pointer]:
        - /url: /messenger.html
      - link "플랫폼" [ref=e30] [cursor=pointer]:
        - /url: /platform.html
    - generic [ref=e37]:
      - generic "에이전트 대시보드로 이동" [ref=e38]: 에이전트 유휴
      - generic [ref=e41]: 실시간 연결됨
    - generic [ref=e44]:
      - link "v2.4.0" [ref=e45] [cursor=pointer]:
        - /url: /platform.html
      - link "~100% 추정" [ref=e46] [cursor=pointer]:
        - /url: /home.html
      - button "알림 (Alt+N)" [ref=e51] [cursor=pointer]
      - button "테마 전환 (Alt+T)" [ref=e55] [cursor=pointer]
  - generic [ref=e58]:
    - complementary [ref=e59]:
      - generic [ref=e60]:
        - button "새 이슈" [ref=e61] [cursor=pointer]
        - button "이슈 목록" [ref=e63] [cursor=pointer]
        - button "가져오기" [ref=e67] [cursor=pointer]
      - generic [ref=e71]: 세션0
      - generic [ref=e74]:
        - text: 세션이 없습니다.라벨
        - code [ref=e75]: ai-fix
        - text: 이슈가 등록되면 자동으로 나타납니다.
    - main [ref=e76]:
      - generic [ref=e77]:
        - heading "세션을 선택하세요" [level=3] [ref=e82]
        - paragraph [ref=e83]: 왼쪽 목록에서 이슈를 고르면 진행 단계, 실시간 활동, 설계안과 변경 내용을 볼 수 있습니다.
```

# Test source

```ts
  3   | import path from 'node:path';
  4   | import { shot } from '../support/shot.ts';
  5   | import { collectConsoleErrors } from '../support/console.ts';
  6   | 
  7   | const TAG = { tag: ['@task:T-033'] };
  8   | const SAMPLE = 'qa-group/qa-sample';
  9   | const ALPHA = 'demo-user/alpha-service';
  10  | const ARCHIVE = 'demo-user/old-archive';
  11  | const BETA = 'demo-user/beta-web';
  12  | const REPO_ROOT = path.resolve(process.cwd(), '..');
  13  | const DAY_MS = 86_400_000;
  14  | const MIN_TOUCH_PX = 44;
  15  | 
  16  | type Issue = { iid: number; title: string; webUrl: string; labels: string[]; author: string; state: string | null; active: boolean; sessionId?: number; blocked?: string };
  17  | type Mock = { targets: string[]; starts: string[]; startResponse: { status: number; body: unknown } | null; requests: string[] };
  18  | 
  19  | function repo(p: string, over: Record<string, unknown> = {}) {
  20  |   return { path: p, defaultBranch: 'main', private: false, archived: false, fork: false,
  21  |     updatedAt: new Date(Date.now() - 3 * DAY_MS).toISOString(), webUrl: `https://github.com/${p}`, ...over };
  22  | }
  23  | const REPOS = [repo(ALPHA, { private: true }), repo(ARCHIVE, { archived: true }), repo(BETA)];
  24  | 
  25  | function issue(iid: number, title: string, over: Partial<Issue> = {}): Issue {
  26  |   return { iid, title, webUrl: `https://github.com/demo-user/x/issues/${iid}`, labels: [], author: 'demo-user', state: null, active: false, ...over };
  27  | }
  28  | const ISSUES: Record<string, Issue[]> = {
  29  |   [ALPHA]: [
  30  |     issue(12, '주문 목록 API 빈 페이지 오류', { labels: ['ai-fix', 'bug'] }),
  31  |     issue(9, '결제 취소 후 재고 복구 누락', { state: 'REVIEW_WAIT', active: true, sessionId: 7 }),
  32  |     issue(5, '외부 사용자가 올린 이슈', { labels: ['ai-fix'], author: 'someone', blocked: '신뢰 작성자가 아니라 처리하지 않습니다' }),
  33  |   ],
  34  |   [BETA]: [issue(31, '로그인 화면 포커스 순서', { labels: ['ai-fix'] }), issue(30, '다크 모드 대비')],
  35  |   [ARCHIVE]: [issue(2, '오타 수정')],
  36  | };
  37  | 
  38  | // Local home paths in /api/system/** and /api/usage become <repo> before the page renders them.
  39  | async function maskSystemPaths(page: Page): Promise<void> {
  40  |   const variants = [REPO_ROOT, REPO_ROOT.replace(/\\/g, '\\\\'), REPO_ROOT.replace(/\\/g, '/')];
  41  |   const generic = /[A-Za-z]:(?:\\\\|\\|\/)+Users(?:\\\\|\\|\/)+[^\\/"\s]+/g;
  42  |   await page.route(/\/api\/(system\/|usage)/, async (route: Route) => {
  43  |     const res = await route.fetch();
  44  |     let text = await res.text();
  45  |     for (const v of variants) text = text.split(v).join('<repo>');
  46  |     text = text.replace(generic, '<repo>');
  47  |     await route.fulfill({ response: res, body: text });
  48  |   });
  49  | }
  50  | 
  51  | async function expectNoAccountName(page: Page): Promise<void> {
  52  |   const account = os.userInfo().username.toLowerCase();
  53  |   for (const frame of page.frames()) {
  54  |     const text = await frame.evaluate(() => document.documentElement.innerHTML);
  55  |     expect(text.toLowerCase()).not.toContain(account);
  56  |   }
  57  | }
  58  | 
  59  | // Fakes repos, issue lists, targets and the start API. Nothing reaches the real target list.
  60  | async function mockIssueApi(page: Page): Promise<Mock> {
  61  |   const m: Mock = { targets: [SAMPLE], starts: [], startResponse: null, requests: [] };
  62  |   const json = (route: Route, body: unknown, status = 200) =>
  63  |     route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
  64  |   await page.route(/\/api\/issues-(repos|list|start|projects)(\?|$)/, async (route) => {
  65  |     const url = new URL(route.request().url());
  66  |     m.requests.push(`${route.request().method()} ${url.pathname}`);
  67  |     if (url.pathname === '/api/issues-repos') return json(route, { ok: true, repos: REPOS, truncated: false, max: 50 });
  68  |     if (url.pathname === '/api/issues-projects') return json(route, { enabled: true, projects: m.targets.map((p) => ({ path: p })) });
  69  |     if (url.pathname === '/api/issues-list') {
  70  |       const project = url.searchParams.get('project') ?? '';
  71  |       const found = REPOS.find((r) => r.path === project);
  72  |       if (!found) return json(route, { ok: false, result: '조회할 수 없는 저장소입니다.' });
  73  |       return json(route, { ok: true, project, label: 'ai-fix', target: m.targets.includes(project), archived: found.archived, issues: ISSUES[project] ?? [] });
  74  |     }
  75  |     const body = route.request().postData() ?? '';
  76  |     m.starts.push(body);
  77  |     if (m.startResponse) return json(route, m.startResponse.body, m.startResponse.status);
  78  |     const params = new URLSearchParams(body);
  79  |     const project = params.get('project') ?? '';
  80  |     const added = !m.targets.includes(project);
  81  |     if (added) m.targets.push(project);
  82  |     return json(route, { ok: true, result: `${project}#${params.get('number')} 큐에 넣었습니다.`, added, ref: `${project}#${params.get('number')}` });
  83  |   });
  84  |   return m;
  85  | }
  86  | 
  87  | async function openList(page: Page): Promise<void> {
  88  |   await page.goto('/agent.html');
  89  |   await page.locator('#btnList').click();
  90  |   await expect(page.locator('#ilDlg')).toBeVisible();
  91  | }
  92  | 
  93  | async function pickRepo(page: Page, name: string): Promise<void> {
  94  |   await page.locator('#ilRepos .ilx-repo', { hasText: name }).click();
  95  |   await expect(page.locator('#ilist .irow').first()).toBeVisible();
  96  | }
  97  | 
  98  | async function expectNoOverflow(page: Page, includeDoc = true): Promise<void> {
  99  |   const o = await page.evaluate(() => {
  100 |     const d = document.getElementById('ilDlg') as HTMLDialogElement;
  101 |     return { doc: document.documentElement.scrollWidth - window.innerWidth, dlg: d.open ? d.scrollWidth - d.clientWidth : 0 };
  102 |   });
> 103 |   if (includeDoc) expect(o.doc).toBeLessThanOrEqual(0);
      |                                 ^ Error: expect(received).toBeLessThanOrEqual(expected)
  104 |   expect(o.dlg).toBeLessThanOrEqual(0);
  105 | }
  106 | 
  107 | test('TC-031 ① 실제 서버 응답(토큰 없음): 토큰 안내·대상 ★·조회 실패 문구·닫으면 요청 없음', TAG, async ({ page }, testInfo) => {
  108 |   const errors = collectConsoleErrors(page);
  109 |   const requests: string[] = [];
  110 |   page.on('request', (r) => { if (/\/api\/issues-(list|repos|start)/.test(r.url())) requests.push(r.url()); });
  111 |   await maskSystemPaths(page);
  112 |   await page.goto('/agent.html');
  113 |   await page.waitForLoadState('networkidle');
  114 |   expect(requests, '창이 닫힌 동안 요청 없음').toHaveLength(0);
  115 |   await page.locator('#btnList').click();
  116 |   await expect(page.locator('#ilNote')).toContainText('GITHUB_TOKEN');
  117 |   const sample = page.locator('#ilRepos .ilx-repo', { hasText: SAMPLE });
  118 |   await expect(sample).toContainText('★');
  119 |   await shot(page, testInfo, 'repos-no-token');
  120 |   await sample.click();
  121 |   await expect(page.locator('#ilist')).not.toContainText('불러오는 중');
  122 |   await expect(page.locator('#ilist')).not.toHaveText('');
  123 |   await expect(page.locator('.toast', { hasText: '대상 프로젝트가 없습니다' })).toHaveCount(0);
  124 |   await page.locator('#ilClose').click();
  125 |   requests.length = 0;
  126 |   await page.waitForTimeout(2500); // proves that no request follows a closed window
  127 |   expect(requests).toHaveLength(0);
  128 |   await expectNoAccountName(page);
  129 |   expect(errors).toEqual([]);
  130 |   await shot(page, testInfo, 'issue-lookup-failed-closed');
  131 | });
  132 | 
  133 | test('TC-031 ② 대상 아닌 저장소 시작: 상태·확인 문구·본문·★·blocked·진행 중', TAG, async ({ page }, testInfo) => {
  134 |   const errors = collectConsoleErrors(page);
  135 |   await maskSystemPaths(page);
  136 |   const mock = await mockIssueApi(page);
  137 |   await openList(page);
  138 |   await pickRepo(page, 'alpha-service');
  139 |   const blocked = page.locator('#ilist .irow', { hasText: '#5' });
  140 |   await expect(blocked).toContainText('처리 안 함');
  141 |   await expect(blocked).toContainText('신뢰 작성자');
  142 |   await expect(blocked.locator('button')).toHaveCount(0);
  143 |   await expect(page.locator('#ilist .irow', { hasText: '#9' })).toContainText('보기');
  144 |   await pickRepo(page, 'beta-web');
  145 |   await expect(page.locator('#ilStatus')).toContainText('대상 아님');
  146 |   let message = '';
  147 |   page.once('dialog', async (d) => { message = d.message(); await d.dismiss(); });
  148 |   await page.locator('#ilist .irow', { hasText: '#31' }).locator('button').click();
  149 |   await expect.poll(() => message).toContain('지금 목록에 1건');
  150 |   expect(mock.starts, '취소하면 시작 요청 없음').toHaveLength(0);
  151 |   await shot(page, testInfo, 'confirm-dismissed');
  152 |   page.once('dialog', (d) => d.accept());
  153 |   await page.locator('#ilist .irow', { hasText: '#31' }).locator('button').click();
  154 |   await expect.poll(() => mock.starts.length).toBe(1);
  155 |   expect(mock.starts[0]).toContain('project=demo-user%2Fbeta-web');
  156 |   expect(mock.starts[0]).toContain('number=31');
  157 |   await expect(page.locator('#ilRepos .ilx-repo', { hasText: 'beta-web' }).locator('.star')).toHaveText('★');
  158 |   await expect(page.locator('#ilMsg')).toContainText('큐에 넣었습니다');
  159 |   expect(errors).toEqual([]);
  160 |   await shot(page, testInfo, 'started-star-added');
  161 | });
  162 | 
  163 | test('TC-031 ③ 보관 저장소: 시작 버튼 비활성', TAG, async ({ page }, testInfo) => {
  164 |   await maskSystemPaths(page);
  165 |   await mockIssueApi(page);
  166 |   await openList(page);
  167 |   await pickRepo(page, 'old-archive');
  168 |   await expect(page.locator('#ilist .irow button').first()).toBeDisabled();
  169 |   await shot(page, testInfo, 'archived-disabled');
  170 | });
  171 | 
  172 | test('TC-031 ④ 시작 응답 ok:false: err 문구·★ 안 달림', TAG, async ({ page }, testInfo) => {
  173 |   await maskSystemPaths(page);
  174 |   const mock = await mockIssueApi(page);
  175 |   mock.startResponse = { status: 409, body: { ok: false, result: '이미 진행 중인 이슈입니다.', added: false } };
  176 |   await openList(page);
  177 |   await pickRepo(page, 'beta-web');
  178 |   page.once('dialog', (d) => d.accept());
  179 |   await page.locator('#ilist .irow', { hasText: '#31' }).locator('button').click();
  180 |   await expect(page.locator('#ilMsg')).toContainText('이미 진행 중');
  181 |   await expect(page.locator('#ilMsg')).toHaveClass(/err/);
  182 |   await expect(page.locator('#ilRepos .ilx-repo', { hasText: 'beta-web' }).locator('.star')).not.toHaveText('★');
  183 |   await shot(page, testInfo, 'start-failed');
  184 | });
  185 | 
  186 | test('TC-031 ⑤ 390px 두 단계 이동', TAG, async ({ page }, testInfo) => {
  187 |   await page.setViewportSize({ width: 390, height: 844 });
  188 |   await maskSystemPaths(page);
  189 |   await mockIssueApi(page);
  190 |   await openList(page);
  191 |   await expect(page.locator('.ilx-right')).toBeHidden();
  192 |   await expect(page.locator('#ilClose')).toBeVisible();
  193 |   await expectNoOverflow(page);
  194 |   await shot(page, testInfo, 'step1-repos');
  195 |   await pickRepo(page, 'alpha-service');
  196 |   await expect(page.locator('.ilx-left')).toBeHidden();
  197 |   await expect(page.locator('#ilist .why')).toBeVisible();
  198 |   await expectNoOverflow(page);
  199 |   const small = await page.evaluate((min) => [...document.querySelectorAll('#ilDlg button, #ilDlg input')]
  200 |     .filter((e) => (e as HTMLElement).offsetParent !== null && !(e as HTMLButtonElement).disabled)
  201 |     .map((e) => ({ id: (e as HTMLElement).id || e.textContent?.trim().slice(0, 10), h: Math.round(e.getBoundingClientRect().height) }))
  202 |     .filter((x) => x.h < min), MIN_TOUCH_PX);
  203 |   expect(small).toEqual([]);
```