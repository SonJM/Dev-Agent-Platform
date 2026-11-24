-- Dev Agent 스키마. 빈 DB 에서 첫 기동 때 실행된다(DROP 문은 제외). 이후 변경은 각 매퍼의 ensureTable() 이 맡는다.

DROP TABLE IF EXISTS mention_reply;
DROP TABLE IF EXISTS chat_summary;
DROP TABLE IF EXISTS chat_message;
DROP TABLE IF EXISTS chat_room_cursor;
DROP TABLE IF EXISTS bot_setting;
DROP TABLE IF EXISTS doc_chunk;
DROP TABLE IF EXISTS issue_event;
DROP TABLE IF EXISTS issue_session;

-- 런타임 설정 (관리 페이지에서 편집 → 재시작 없이 반영). 예: auto_room_keys
CREATE TABLE bot_setting (
    key         VARCHAR(64)  PRIMARY KEY,
    value       TEXT,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 수집 메시지. 방·사용자·메시지 id 는 메신저 어댑터가 정하는 문자열(숫자, Slack 채널 id·ts, Discord snowflake 등)이라
-- 크기를 비교하지 않는다. 정렬·참조는 로컬 적재 순번 seq 로 한다.
CREATE TABLE chat_message (
    seq            BIGSERIAL    PRIMARY KEY,       -- 로컬 적재 순번
    message_id     VARCHAR(64)  NOT NULL,          -- 메신저의 메시지 id(방 안에서 고유)
    room_id        VARCHAR(64)  NOT NULL,
    room_type      VARCHAR(16)  NOT NULL,          -- self | single | group
    sender_id      VARCHAR(64),
    sender_name    VARCHAR(100),
    body           TEXT,
    sent_at        TIMESTAMPTZ  NOT NULL,
    mentioned_me   BOOLEAN      NOT NULL DEFAULT FALSE,
    raw_json       JSONB,
    collected_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (room_type, room_id, message_id)
);
CREATE INDEX idx_chat_message_room_time ON chat_message (room_id, room_type, sent_at);
CREATE INDEX idx_chat_message_mention ON chat_message (mentioned_me) WHERE mentioned_me = TRUE;

-- 방별 수집 커서(마지막으로 받은 메시지 id)
CREATE TABLE chat_room_cursor (
    room_id           VARCHAR(64)  NOT NULL,
    room_type         VARCHAR(16)  NOT NULL,       -- self | single | group
    room_name         VARCHAR(200),
    last_message_id   VARCHAR(64),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (room_id, room_type)
);

-- 요약 결과
CREATE TABLE chat_summary (
    id              BIGSERIAL    PRIMARY KEY,
    room_id         VARCHAR(64)  NOT NULL,
    room_type       VARCHAR(16)  NOT NULL,
    from_at         TIMESTAMPTZ  NOT NULL,
    to_at           TIMESTAMPTZ  NOT NULL,
    message_count   INT          NOT NULL,
    summary         TEXT         NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 멘션 응답 이력 (중복 응답 방지 키)
CREATE TABLE mention_reply (
    message_seq    BIGINT       PRIMARY KEY REFERENCES chat_message(seq),
    room_id        VARCHAR(64)  NOT NULL,
    mode           VARCHAR(16)  NOT NULL,          -- DRAFT | AUTO | SKIPPED
    reply_text     TEXT,
    skip_reason    VARCHAR(200),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 사내 개발문서 섹션 (MkDocs search_index.json 에서 전면 갱신, RAG 검색 대상)
-- 앱 기동 시 DocChunkMapper.ensureTable() 로도 멱등 생성되므로 기존 DB 에도 자동 추가된다.
CREATE TABLE doc_chunk (
    id         BIGSERIAL    PRIMARY KEY,
    location   TEXT         NOT NULL UNIQUE,       -- MkDocs location (언어는 "ko/" prefix), 딥링크 키
    url        TEXT         NOT NULL,              -- siteBase + location
    title      TEXT         NOT NULL,
    body       TEXT         NOT NULL,              -- HTML 제거한 평문
    lang       VARCHAR(8),                         -- ko | en (vi 제외)
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- GitLab 이슈 수정 에이전트 세션 (이슈 1건 = 1행). 상태: NEW → ANALYZING → DESIGN_WAIT → FIXING → REVIEW_WAIT → PUSHING → DONE (ABORTED/FAILED)
-- 앱 기동 시 IssueSessionMapper.ensureTable() 로도 멱등 생성되므로 기존 DB 에도 자동 추가된다.
CREATE TABLE issue_session (
    id                     BIGSERIAL    PRIMARY KEY,
    project_path           TEXT         NOT NULL,          -- "group/repo"
    project_id             BIGINT       NOT NULL,
    issue_iid              BIGINT       NOT NULL,
    issue_title            TEXT,
    issue_url              TEXT,
    default_branch         TEXT,
    state                  VARCHAR(16)  NOT NULL,
    claude_session_id      TEXT,                           -- claude -p --session-id/--resume (이슈당 1세션)
    branch_name            TEXT,                           -- ai/issue-<iid>-<slug>
    design_text            TEXT,                           -- 마지막 설계안
    change_summary         TEXT,                           -- 마지막 변경 요약/검증/커밋 메시지
    commit_sha             TEXT,
    mr_url                 TEXT,
    last_error             TEXT,
    feedback_rounds        INT          NOT NULL DEFAULT 0,
    verify_rounds          INT          NOT NULL DEFAULT 0,  -- 검증 실행 횟수(자동 재수정 포함)
    verify_result          TEXT,                             -- 마지막 검증 보고(봇 검사 + 리뷰어 판정)
    work_type              VARCHAR(16),                      -- 컨벤션 작업 유형 = 브랜치 접두어(feature/fix/refactor/doc/hotfix)
    work_id                VARCHAR(64),                      -- 일감번호(이슈 제목의 Redmine 번호, 없으면 issue-<iid>)
    base_branch            TEXT,                             -- 작업트리 기준 브랜치(develop / hotfix 는 main)
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (project_path, issue_iid)
);

-- 이슈 세션 타임라인/활동 로그 (모니터 화면 /agent.html 원천; 기동 시 멱등 생성)
CREATE TABLE issue_event (
    id          BIGSERIAL    PRIMARY KEY,
    session_id  BIGINT       NOT NULL,                      -- issue_session.id
    kind        VARCHAR(16)  NOT NULL,                      -- STATE|INFO|REPLY|CLAUDE|TOOL|TOOL_RESULT|TEXT|GIT|GITLAB|ERROR
    message     TEXT,                                       -- 한 줄 요약
    detail      TEXT,                                       -- 프롬프트/출력/diff 등 긴 본문
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_issue_event_session ON issue_event (session_id, id);

-- Claude 호출별 토큰 사용량(구독제: 비용 대신 토큰). source: agent/ops/chat, limit_hit: 사용량 한도 메시지를 본 호출
CREATE TABLE IF NOT EXISTS claude_usage (
    id            BIGSERIAL    PRIMARY KEY,
    at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    source        VARCHAR(16)  NOT NULL,
    stage         VARCHAR(16),
    model         VARCHAR(32),
    ref           TEXT,
    input_tokens  BIGINT       NOT NULL DEFAULT 0,
    output_tokens BIGINT       NOT NULL DEFAULT 0,
    cache_read    BIGINT       NOT NULL DEFAULT 0,
    cache_create  BIGINT       NOT NULL DEFAULT 0,
    limit_hit     BOOLEAN      NOT NULL DEFAULT false
);
CREATE INDEX IF NOT EXISTS idx_claude_usage_at ON claude_usage (at);

-- 질의(리서치) 워크플로우: 질문 1건 = 1행. 루트(parent_id NULL, root_id = id)와 후속 질문(같은 root_id, 루트의 Claude 세션 --resume)
CREATE TABLE IF NOT EXISTS research_job (
    id                 BIGSERIAL    PRIMARY KEY,
    parent_id          BIGINT,
    root_id            BIGINT       NOT NULL DEFAULT 0,
    question           TEXT         NOT NULL,
    projects           TEXT         NOT NULL,            -- 쉼표 구분 group/repo (첫 항목이 작업 디렉터리)
    model              VARCHAR(32),
    effort             VARCHAR(16),
    state              VARCHAR(16)  NOT NULL,            -- NEW/RUNNING/DONE/FAILED/ABORTED
    claude_session_id  TEXT,
    result_md          TEXT,                             -- 결과 문서(마크다운)
    last_error         TEXT,
    turns              INT          NOT NULL DEFAULT 0,
    input_tokens       BIGINT       NOT NULL DEFAULT 0,
    output_tokens      BIGINT       NOT NULL DEFAULT 0,
    elapsed_ms         BIGINT       NOT NULL DEFAULT 0,
    notify             BOOLEAN      NOT NULL DEFAULT false, -- 완료 시 자기방 알림(메신저 접수)
    requester          VARCHAR(32),                      -- dashboard / messenger
    verify             BOOLEAN      NOT NULL DEFAULT false, -- 2차 검증 수행 여부
    verify_state       VARCHAR(16),                      -- PASS / FIXED / FAIL / SKIPPED
    verify_md          TEXT,                             -- 검증자 보고서
    kind               VARCHAR(16)  NOT NULL DEFAULT 'doc', -- 결과 형식: doc(조사 문서) / issue(GitLab 이슈 초안)
    attachments        TEXT,                             -- 첨부 이미지 이름(쉼표 구분). 파일은 작업 디렉터리 research-attach/<id>/
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    started_at         TIMESTAMPTZ,
    finished_at        TIMESTAMPTZ,
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_research_job_root ON research_job (root_id, id);
-- 질의 이벤트는 issue_event 를 공유하며 session_id = 1000000000 + research_job.id

-- 플랫폼 관리(자기 유지보수) 대화: 요청 1건 = 1행. 같은 대화(thread_id = 첫 행의 id)는 Claude 세션 하나를 --resume 으로 이어간다
CREATE TABLE IF NOT EXISTS maintain_turn (
    id                 BIGSERIAL    PRIMARY KEY,
    thread_id          BIGINT       NOT NULL DEFAULT 0,
    question           TEXT         NOT NULL,
    answer_md          TEXT,                             -- 답변(마크다운)
    state              VARCHAR(16)  NOT NULL,            -- NEW/RUNNING/DONE/FAILED/ABORTED
    claude_session_id  TEXT,
    model              VARCHAR(32),                      -- NULL 이면 에이전트 설정의 '개발' 모델
    last_error         TEXT,
    turns              INT          NOT NULL DEFAULT 0,
    input_tokens       BIGINT       NOT NULL DEFAULT 0,
    output_tokens      BIGINT       NOT NULL DEFAULT 0,
    elapsed_ms         BIGINT       NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    started_at         TIMESTAMPTZ,
    finished_at        TIMESTAMPTZ,
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_maintain_turn_thread ON maintain_turn (thread_id, id);
-- 플랫폼 관리 이벤트도 issue_event 를 공유하며 session_id = 2000000000 + maintain_turn.id

-- 관리 페이지에서 등록한 사용자 정의 MCP 서버(내장 opensearch/postgres/k3s-ops 와 함께 work/mcp-servers.json 에 합쳐진다). 비밀값은 ${ENV} 자리표시자만.
CREATE TABLE IF NOT EXISTS mcp_server (
    name         VARCHAR(32)  PRIMARY KEY,                -- 도구 접두어 mcp__<name>__
    type         VARCHAR(8)   NOT NULL,                   -- http | sse | stdio
    url          TEXT,
    command      TEXT,
    args         TEXT,                                    -- 한 줄에 하나
    headers      TEXT,                                    -- "Key: Value" 한 줄에 하나
    tools        TEXT,                                    -- 허용 도구(비우면 서버 단위)
    description  TEXT,
    enabled      BOOLEAN      NOT NULL DEFAULT true,
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 플랫폼 스킬: 에이전트 시스템 프롬프트에 싣는 재사용 지침. stages = analyze,fix,verify,research (비우면 전 단계)
CREATE TABLE IF NOT EXISTS agent_skill (
    name         VARCHAR(48)  PRIMARY KEY,
    description  TEXT         NOT NULL,
    body         TEXT         NOT NULL,
    stages       TEXT,
    enabled      BOOLEAN      NOT NULL DEFAULT true,
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 루틴(예약 질의): 요일·시각마다 같은 질문을 질의 워크플로우에 접수 (RoutineMapper.ensureTable 과 동일 정의)
CREATE TABLE IF NOT EXISTS routine (
    id           BIGSERIAL    PRIMARY KEY,
    name         VARCHAR(80)  NOT NULL,
    question     TEXT         NOT NULL,
    projects     TEXT         NOT NULL,
    model        VARCHAR(16),
    effort       VARCHAR(16),
    verify       BOOLEAN,
    days         VARCHAR(40),
    run_at       VARCHAR(5)   NOT NULL,
    notify       BOOLEAN      NOT NULL DEFAULT false,
    enabled      BOOLEAN      NOT NULL DEFAULT true,
    last_run_at  TIMESTAMPTZ,
    last_job_id  BIGINT,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 플랫폼 알림함(상단 종 버튼): 이슈 에이전트·질의·시스템 알림. 메신저와 무관하게 남는다. 최근 200건만 보관, 읽음 여부는 브라우저가 기억
CREATE TABLE IF NOT EXISTS notification (
    id          BIGSERIAL    PRIMARY KEY,
    kind        VARCHAR(16)  NOT NULL,
    level       VARCHAR(8)   NOT NULL,
    title       VARCHAR(200) NOT NULL,
    body        TEXT,
    link        VARCHAR(200),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
