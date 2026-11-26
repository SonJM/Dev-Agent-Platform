# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: tasks\T-012.spec.ts >> TC-T012-1 에이전트 화면: 세션 목록 issueNumber·PR 배지, 상세 이슈·PR 링크
- Location: tasks\T-012.spec.ts:20:1

# Error details

```
Error: expect(locator).not.toContainText(expected) failed

Locator: locator('#detail')
Expected pattern: not /MR/
Received string: "
        
          qa-group/qa-sample이슈 #9002리뷰 확인 대기
          변경 확인 대기 이슈 PR 있음
          이슈fix/9002Pull Request
          경과20s피드백1회Claude 실행0회도구 호출1회토큰—시작11. 26. 08:59
          
        

        ✓분석✓설계 확인✓코드 수정✓검증5리뷰 확인6푸시 · PR7완료opus · 리뷰 확인 대기
  
  
  
  
  
  
  z

        

        
          
            변경 내용을 확인해 주세요. 「진행」이면 컨벤션 브랜치(fix/일감번호 등)로 커밋·푸시하고 Draft MR을 만듭니다. 입력칸에 글을 쓴 채 「진행」을 누르면 푸시하지 않고 그 내용부터 반영합니다. 피드백을 보내면 추가 수정하고, 「검증」「재검증」이라고만 보내면 수정 없이 검증만 다시 돌립니다.
            
            
              진행
              피드백 보내기 ⏎
              중단
              동시 진행 중: 자기방에서는 「repo#1 진행」처럼 대상을 앞에 붙여 답합니다
            
          
        

        
          활동 로그 10
          설계안
          변경 요약
          검증 
          Diff
          서브 에이전트 
          타임라인 7
        

        
          
             도구 호출
             중간 텍스트
             도구 결과
            
             자동 스크롤
            줄을 클릭하면 상세를 펼칩니다
          
          10:59:42⇢이벤트 STATE10:59:42ℹ이벤트 INFO10:59:42💬이벤트 REPLY10:59:42✦이벤트 CLAUDE10:59:42›이벤트 TOOL10:59:42↳이벤트 TOOL_RESULT10:59:42…이벤트 TEXT10:59:42⎇이벤트 GIT10:59:42⬡원격 이벤트 REMOTE10:59:42✖이벤트 ERROR
        
        아직 설계안이 없습니다. 분석이 끝나면 여기에 표시됩니다.
        아직 변경 요약이 없습니다. 코드 수정이 끝나면 표시됩니다.
        아직 검증 결과가 없습니다. 코드 수정이 끝나면 봇 검사(빌드·JS/JSP 문법)와 독립 리뷰어 판정이 여기 표시됩니다.
        
        
          
          이 세션은 서브 에이전트를 띄우지 않았습니다.
        
        
      11. 26. 10:59STATE이벤트 STATE
      11. 26. 10:59INFO이벤트 INFO
      11. 26. 10:59REPLY이벤트 REPLY
      11. 26. 10:59CLAUDE이벤트 CLAUDE
      11. 26. 10:59GIT이벤트 GIT
      11. 26. 10:59REMOTE원격 이벤트 REMOTE
      11. 26. 10:59ERROR이벤트 ERROR
      "
Timeout: 5000ms

Call log:
  - Expect "soft not toContainText" locator('#detail') with timeout 5000ms
  - waiting for locator('#detail')
    13 × locator resolved to <div id="detail" class="swap-in">…</div>
       - unexpected value "
        
          qa-group/qa-sample이슈 #9002리뷰 확인 대기
          변경 확인 대기 이슈 PR 있음
          이슈fix/9002Pull Request
          경과20s피드백1회Claude 실행0회도구 호출1회토큰—시작11. 26. 08:59
          
        

        ✓분석✓설계 확인✓코드 수정✓검증5리뷰 확인6푸시 · PR7완료opus · 리뷰 확인 대기
  
  
  
  
  
  
  z

        

        
          
            변경 내용을 확인해 주세요. 「진행」이면 컨벤션 브랜치(fix/일감번호 등)로 커밋·푸시하고 Draft MR을 만듭니다. 입력칸에 글을 쓴 채 「진행」을 누르면 푸시하지 않고 그 내용부터 반영합니다. 피드백을 보내면 추가 수정하고, 「검증」「재검증」이라고만 보내면 수정 없이 검증만 다시 돌립니다.
            
            
              진행
              피드백 보내기 ⏎
              중단
              동시 진행 중: 자기방에서는 「repo#1 진행」처럼 대상을 앞에 붙여 답합니다
            
          
        

        
          활동 로그 10
          설계안
          변경 요약
          검증 
          Diff
          서브 에이전트 
          타임라인 7
        

        
          
             도구 호출
             중간 텍스트
             도구 결과
            
             자동 스크롤
            줄을 클릭하면 상세를 펼칩니다
          
          10:59:42⇢이벤트 STATE10:59:42ℹ이벤트 INFO10:59:42💬이벤트 REPLY10:59:42✦이벤트 CLAUDE10:59:42›이벤트 TOOL10:59:42↳이벤트 TOOL_RESULT10:59:42…이벤트 TEXT10:59:42⎇이벤트 GIT10:59:42⬡원격 이벤트 REMOTE10:59:42✖이벤트 ERROR
        
        아직 설계안이 없습니다. 분석이 끝나면 여기에 표시됩니다.
        아직 변경 요약이 없습니다. 코드 수정이 끝나면 표시됩니다.
        아직 검증 결과가 없습니다. 코드 수정이 끝나면 봇 검사(빌드·JS/JSP 문법)와 독립 리뷰어 판정이 여기 표시됩니다.
        
        
          
          이 세션은 서브 에이전트를 띄우지 않았습니다.
        
        
      11. 26. 10:59STATE이벤트 STATE
      11. 26. 10:59INFO이벤트 INFO
      11. 26. 10:59REPLY이벤트 REPLY
      11. 26. 10:59CLAUDE이벤트 CLAUDE
      11. 26. 10:59GIT이벤트 GIT
      11. 26. 10:59REMOTE원격 이벤트 REMOTE
      11. 26. 10:59ERROR이벤트 ERROR
      "

```

```yaml
- text: "qa-group/qa-sample 이슈 #9002 리뷰 확인 대기"
- heading "변경 확인 대기 이슈 PR 있음" [level=1]
- link "이슈":
  - /url: http://127.0.0.1:9/qa-group/qa-sample/issues/9002
  - img
  - text: 이슈
- link "fix/9002":
  - /url: http://127.0.0.1:9/qa-group/qa-sample/tree/fix/9002
  - img
  - code: fix/9002
- link "Pull Request":
  - /url: http://127.0.0.1:9/qa-group/qa-sample/pull/9002
  - img
  - text: Pull Request
- text: 경과 20s 피드백 1회 Claude 실행 0회 도구 호출 1회 토큰 — 시작 11. 26. 08:59 ✓ 분석 ✓ 설계 확인 ✓ 코드 수정 ✓ 검증 5 리뷰 확인 6 푸시 · PR 7 완료
- img
- text: 변경 내용을 확인해 주세요. 「진행」이면 컨벤션 브랜치(
- code: fix/일감번호
- text: 등)로 커밋·푸시하고 Draft MR을 만듭니다. 입력칸에 글을 쓴 채 「진행」을 누르면 푸시하지 않고 그 내용부터 반영합니다. 피드백을 보내면 추가 수정하고, 「검증」「재검증」이라고만 보내면 수정 없이 검증만 다시 돌립니다.
- textbox "피드백을 쓰면 설계/수정에 반영됩니다. Enter 보내기 · Shift+Enter 줄바꿈"
- button "진행":
  - img
  - text: 진행
- button "피드백 보내기 ⏎":
  - img
  - text: 피드백 보내기 ⏎
- button "중단":
  - img
  - text: 중단
- text: "동시 진행 중: 자기방에서는 「repo#1 진행」처럼 대상을 앞에 붙여 답합니다"
- tablist:
  - button "활동 로그 10"
  - button "설계안"
  - button "변경 요약"
  - button "검증"
  - button "Diff"
  - button "서브 에이전트"
  - button "타임라인 7"
- checkbox "도구 호출" [checked]
- text: 도구 호출
- checkbox "중간 텍스트" [checked]
- text: 중간 텍스트
- checkbox "도구 결과" [checked]
- text: 도구 결과
- checkbox "자동 스크롤" [checked]
- text: 자동 스크롤 줄을 클릭하면 상세를 펼칩니다 10:59:42 ⇢ 이벤트 STATE 10:59:42 ℹ 이벤트 INFO 10:59:42 💬 이벤트 REPLY 10:59:42 ✦ 이벤트 CLAUDE 10:59:42 › 이벤트 TOOL 10:59:42 ↳ 이벤트 TOOL_RESULT 10:59:42 … 이벤트 TEXT 10:59:42 ⎇ 이벤트 GIT 10:59:42 ⬡ 원격 이벤트 REMOTE 10:59:42 ✖ 이벤트 ERROR
```

# Test source

```ts
  1   | import { test, expect, type Page } from '@playwright/test';
  2   | import { shot } from '../support/shot.ts';
  3   | import { collectConsoleErrors } from '../support/console.ts';
  4   | 
  5   | // Seeded by e2e/seed/IssueSessions.java (project qa-group/qa-sample, issue numbers 9001-9006).
  6   | type Session = { id: number; issueNumber: number; projectPath: string; prUrl?: string | null; state: string };
  7   | const PROJECT = 'qa-group/qa-sample';
  8   | const FORBIDDEN = /GitLab|\bMR\b|issueIid|mrUrl/;
  9   | 
  10  | async function seededSession(page: Page, issueNumber: number): Promise<Session> {
  11  |   const res = await page.request.get('/api/agent/overview?limit=80');
  12  |   expect(res.status()).toBe(200);
  13  |   const body = (await res.json()) as { enabled: boolean; sessions: Session[] };
  14  |   expect(body.enabled).toBe(true);
  15  |   const found = body.sessions.find((s) => s.projectPath === PROJECT && s.issueNumber === issueNumber);
  16  |   if (!found) throw new Error(`seeded session ${issueNumber} missing from overview`);
  17  |   return found;
  18  | }
  19  | 
  20  | test('TC-T012-1 에이전트 화면: 세션 목록 issueNumber·PR 배지, 상세 이슈·PR 링크', { tag: ['@task:T-012'] }, async ({ page }, testInfo) => {
  21  |   const errors = collectConsoleErrors(page);
  22  |   const review = await seededSession(page, 9002);
  23  |   const wait = await seededSession(page, 9001);
  24  |   expect(review.prUrl).toContain('/pull/9002');
  25  |   expect(wait.prUrl ?? null).toBeNull();
  26  | 
  27  |   await page.goto('/agent.html');
  28  |   const reviewItem = page.locator('#slist .sitem').filter({ hasText: '#9002' });
  29  |   const waitItem = page.locator('#slist .sitem').filter({ hasText: '#9001' });
  30  |   await expect(reviewItem).toContainText('qa-sample');
  31  |   await expect(reviewItem.locator('.r3 span', { hasText: /^PR$/ })).toBeVisible();
  32  |   await expect(waitItem.locator('.r3 span', { hasText: /^PR$/ })).toHaveCount(0);
  33  |   await expect(page.locator('#slist')).not.toContainText('undefined');
  34  |   await shot(page, testInfo, 'agent-list');
  35  | 
  36  |   await reviewItem.click();
  37  |   await expect(page.locator('#dProj')).toHaveText(PROJECT);
  38  |   await expect(page.locator('#dIid')).toHaveText('이슈 #9002');
  39  |   const prLink = page.locator('#dLinks a', { hasText: 'Pull Request' });
  40  |   await expect(prLink).toHaveAttribute('href', /\/qa-group\/qa-sample\/pull\/9002$/);
  41  |   await expect(page.locator('#dLinks a', { hasText: /^이슈$/ })).toHaveAttribute('href', /\/issues\/9002$/);
  42  |   await expect(page.locator('#dLinks')).not.toContainText(FORBIDDEN);
  43  |   // Scope: "MR" -> "PR" in screen text. The review-wait guidance must not name an MR either.
> 44  |   await expect.soft(page.locator('#detail')).not.toContainText(/MR/);
      |                                                  ^ Error: expect(locator).not.toContainText(expected) failed
  45  |   await shot(page, testInfo, 'agent-detail-pr-link');
  46  | 
  47  |   // kind REMOTE event renders in the activity log with its own icon and colour.
  48  |   const remote = page.locator('#log .ln.k-REMOTE').first();
  49  |   await expect(remote).toBeVisible();
  50  |   await expect(remote.locator('.ic')).toHaveText('⬡');
  51  |   await expect(remote.locator('.m')).toHaveCSS('color', 'rgb(253, 186, 116)');
  52  |   await expect(remote).toContainText('원격 이벤트');
  53  |   await page.locator('#log').scrollIntoViewIfNeeded();
  54  |   await expect(remote).toBeInViewport();
  55  |   await shot(page, testInfo, 'agent-remote-event');
  56  |   await page.locator('#log').screenshot({ path: testInfo.outputPath('03b-agent-log-element.png') });
  57  | 
  58  |   // A session without a PR shows no Pull Request link.
  59  |   await waitItem.click();
  60  |   await expect(page.locator('#dIid')).toHaveText('이슈 #9001');
  61  |   await expect(page.locator('#dLinks a', { hasText: 'Pull Request' })).toHaveCount(0);
  62  | 
  63  |   // Timeline tab also renders the REMOTE event (class k-REMOTE).
  64  |   await reviewItem.click();
  65  |   await page.locator('button.tab[data-p="tl"]').click();
  66  |   await expect(page.locator('#tl .ev.k-REMOTE').first()).toBeVisible();
  67  |   await shot(page, testInfo, 'agent-timeline-remote');
  68  |   expect(errors).toEqual([]);
  69  | });
  70  | 
  71  | test('TC-T012-2 홈·메신저·플랫폼: 이슈 표기와 GitHub 용어', { tag: ['@task:T-012'] }, async ({ page }, testInfo) => {
  72  |   const errors = collectConsoleErrors(page);
  73  |   const wait = await seededSession(page, 9001);
  74  | 
  75  |   await page.goto('/home.html');
  76  |   const waitCallout = page.locator('#issueActive, .callout.warn').filter({ hasText: '#9001' }).first();
  77  |   await expect(waitCallout).toBeVisible();
  78  |   await expect(page.locator('#issueActive')).toContainText(`${PROJECT}#9001`);
  79  |   await expect(page.locator('#issueActive')).not.toContainText('undefined');
  80  |   await expect(page.locator('#issueRecent .sess').first()).toContainText(/qa-(sample|boundary|bulk)#\d+/);
  81  |   await expect(page.locator('#issueRecent')).not.toContainText('undefined');
  82  |   await expect(page.locator('#issueCount')).not.toHaveText('0');
  83  |   await shot(page, testInfo, 'home-sessions');
  84  | 
  85  |   await page.goto('/messenger.html');
  86  |   await expect(page.getByText('이슈 owner/repo#12')).toBeVisible();
  87  |   await expect(page.getByText('질문 owner/repo: 질문 내용')).toBeVisible();
  88  |   await expect(page.locator('body')).not.toContainText(FORBIDDEN);
  89  |   await shot(page, testInfo, 'messenger-examples');
  90  | 
  91  |   await page.goto('/platform.html');
  92  |   await expect(page.locator('#projIn')).toHaveAttribute('placeholder', '추가: owner/repo');
  93  |   await expect(page.locator('body')).not.toContainText(FORBIDDEN);
  94  |   expect(wait.state).toBe('DESIGN_WAIT');
  95  |   await shot(page, testInfo, 'platform-project-input');
  96  | 
  97  |   await page.goto('/research.html');
  98  |   await expect(page.locator('body')).not.toContainText(FORBIDDEN);
  99  |   await shot(page, testInfo, 'research');
  100 |   expect(errors).toEqual([]);
  101 | });
  102 | 
```