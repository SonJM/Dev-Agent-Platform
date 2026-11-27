package io.github.lib0823.devagent.issue;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface IssueSessionMapper {

    /** 기존 DB 에도 자동 추가되도록 기동 시 멱등 생성. (db/schema.sql 과 동일 정의) */
    @Update("""
            CREATE TABLE IF NOT EXISTS issue_session (
                id                     BIGSERIAL    PRIMARY KEY,
                project_path           TEXT         NOT NULL,
                issue_number           BIGINT       NOT NULL,
                issue_title            TEXT,
                issue_url              TEXT,
                default_branch         TEXT,
                state                  VARCHAR(16)  NOT NULL,
                claude_session_id      TEXT,
                branch_name            TEXT,
                design_text            TEXT,
                change_summary         TEXT,
                commit_sha             TEXT,
                pr_url                 TEXT,
                last_error             TEXT,
                feedback_rounds        INT          NOT NULL DEFAULT 0,
                verify_rounds          INT          NOT NULL DEFAULT 0,
                verify_result          TEXT,
                work_type              VARCHAR(16),
                base_branch            TEXT,
                created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
                updated_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
                UNIQUE (project_path, issue_number)
            )
            """)
    void ensureTable();

    /**
     * 기존 DB 맞춤(멱등). ensureTable 다음에 호출. 옛 이름 컬럼을 새 이름으로 바꾸고(issue_iid → issue_number, mr_url → pr_url),
     * 더 쓰지 않는 컬럼(project_id, work_id)을 지우고, 뒤늦게 생긴 컬럼을 보충한다.
     */
    @Update("""
            DO $$
            DECLARE
                pair  TEXT[];
                has_old BOOLEAN;
                has_new BOOLEAN;
            BEGIN
                -- 옛만 있음: RENAME / 새만 있음: 그대로 / 둘 다 있음: 새 쪽 NULL 을 옛 값으로 채우고 옛 컬럼 DROP
                FOREACH pair SLICE 1 IN ARRAY ARRAY[ARRAY['issue_iid', 'issue_number'], ARRAY['mr_url', 'pr_url']] LOOP
                    SELECT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = current_schema()
                                   AND table_name = 'issue_session' AND column_name = pair[1]) INTO has_old;
                    SELECT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = current_schema()
                                   AND table_name = 'issue_session' AND column_name = pair[2]) INTO has_new;
                    IF has_old AND NOT has_new THEN
                        EXECUTE format('ALTER TABLE issue_session RENAME COLUMN %I TO %I', pair[1], pair[2]);
                    ELSIF has_old AND has_new THEN
                        EXECUTE format('UPDATE issue_session SET %I = %I WHERE %I IS NULL', pair[2], pair[1], pair[2]);
                        EXECUTE format('ALTER TABLE issue_session DROP COLUMN %I', pair[1]);
                    END IF;
                END LOOP;
                ALTER TABLE issue_session
                    DROP COLUMN IF EXISTS project_id,
                    DROP COLUMN IF EXISTS work_id,
                    ADD COLUMN IF NOT EXISTS verify_rounds INT NOT NULL DEFAULT 0,
                    ADD COLUMN IF NOT EXISTS verify_result TEXT,
                    ADD COLUMN IF NOT EXISTS work_type VARCHAR(16),
                    ADD COLUMN IF NOT EXISTS base_branch TEXT;
            END
            $$
            """)
    void ensureColumns();

    @Insert("""
            INSERT INTO issue_session (project_path, issue_number, issue_title, issue_url, default_branch, state)
            VALUES (#{projectPath}, #{issueNumber}, #{issueTitle}, #{issueUrl}, #{defaultBranch}, #{state})
            ON CONFLICT (project_path, issue_number) DO NOTHING
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertIgnore(IssueSession s);

    @Update("""
            UPDATE issue_session SET
                state = #{state}, claude_session_id = #{claudeSessionId}, branch_name = #{branchName},
                design_text = #{designText}, change_summary = #{changeSummary}, commit_sha = #{commitSha},
                pr_url = #{prUrl}, last_error = #{lastError},
                feedback_rounds = #{feedbackRounds}, verify_rounds = #{verifyRounds}, verify_result = #{verifyResult}, work_type = #{workType}, base_branch = #{baseBranch}, issue_title = #{issueTitle}, updated_at = now()
            WHERE id = #{id}
            """)
    int update(IssueSession s);

    String COLS = """
            id, project_path, issue_number, issue_title, issue_url, default_branch, state,
            claude_session_id, branch_name, design_text, change_summary, commit_sha, pr_url, last_error,
            feedback_rounds, verify_rounds, verify_result, work_type, base_branch, created_at, updated_at
            """;

    @Select("SELECT " + COLS + " FROM issue_session WHERE id = #{id}")
    IssueSession find(@Param("id") long id);

    @Select("SELECT " + COLS + " FROM issue_session WHERE project_path = #{projectPath} AND issue_number = #{iid}")
    IssueSession findByIssue(@Param("projectPath") String projectPath, @Param("iid") long iid);

    /** 진행 중(사용자와 상호작용하는) 세션 — 동시에 최대 1건. 선분석(PREPARING/READY)은 제외. */
    @Select("SELECT " + COLS + " FROM issue_session WHERE state NOT IN ('NEW','PREPARING','READY','DONE','ABORTED','FAILED') "
            + "ORDER BY updated_at DESC LIMIT 1")
    IssueSession findActive();

    /** 활성 세션 전부(동시 처리). 오래된 순. */
    @Select("SELECT " + COLS + " FROM issue_session WHERE state NOT IN ('NEW','PREPARING','READY','DONE','ABORTED','FAILED') "
            + "ORDER BY created_at ASC")
    List<IssueSession> findActiveAll();

    /** 사용자 확인을 기다리는 세션 전부. 오래된 순. */
    @Select("SELECT " + COLS + " FROM issue_session WHERE state IN ('DESIGN_WAIT','REVIEW_WAIT') ORDER BY created_at ASC")
    List<IssueSession> findWaiting();

    @Select("SELECT " + COLS + " FROM issue_session WHERE state = 'NEW' ORDER BY created_at ASC LIMIT 1")
    IssueSession findNextNew();

    @Select("SELECT " + COLS + " FROM issue_session WHERE state = 'READY' ORDER BY created_at ASC LIMIT 1")
    IssueSession findReady();

    @Select("SELECT " + COLS + " FROM issue_session WHERE state = 'PREPARING' ORDER BY created_at ASC LIMIT 1")
    IssueSession findPreparing();

    /** 전체 갱신(조건부): 현재 DB 상태가 expected 일 때만. 선분석 워커가 뒤늦게 결과를 쓸 때 ABORTED 를 덮지 않도록. */
    @Update("""
            UPDATE issue_session SET
                state = #{s.state}, claude_session_id = #{s.claudeSessionId}, branch_name = #{s.branchName},
                design_text = #{s.designText}, change_summary = #{s.changeSummary}, commit_sha = #{s.commitSha},
                pr_url = #{s.prUrl}, last_error = #{s.lastError},
                feedback_rounds = #{s.feedbackRounds}, verify_rounds = #{s.verifyRounds}, verify_result = #{s.verifyResult}, work_type = #{s.workType}, base_branch = #{s.baseBranch}, issue_title = #{s.issueTitle}, updated_at = now()
            WHERE id = #{s.id} AND state = #{expected}
            """)
    int updateIf(@Param("s") IssueSession s, @Param("expected") String expected);

    /** 상태 선점(원자적): 메인 워커와 선분석 워커가 같은 NEW 세션을 동시에 집지 않도록 한다. 반환 1이면 선점 성공. */
    @Update("UPDATE issue_session SET state = #{to}, updated_at = now() WHERE id = #{id} AND state = #{from}")
    int claimState(@Param("id") long id, @Param("from") String from, @Param("to") String to);

    /**
     * 종료된 세션(DONE/ABORTED/FAILED)의 수동 재시작(원자적): 종료 상태일 때만 NEW 로 되돌리고 작업 결과 필드를 비운다.
     * 반환 0 이면 그 사이 다른 요청이 이미 재시작했거나 진행 중이라는 뜻 — 호출측은 아무것도 지우지 않고 거부한다.
     */
    @Update("""
            UPDATE issue_session SET
                state = 'NEW', claude_session_id = NULL, branch_name = NULL, design_text = NULL, change_summary = NULL,
                commit_sha = NULL, pr_url = NULL, last_error = NULL, feedback_rounds = 0, issue_title = #{title}, updated_at = now()
            WHERE id = #{id} AND state IN ('DONE', 'ABORTED', 'FAILED')
            """)
    int restartIfFinished(@Param("id") long id, @Param("title") String title);

    @Select("SELECT " + COLS + " FROM issue_session ORDER BY updated_at DESC LIMIT #{limit}")
    List<IssueSession> recent(@Param("limit") int limit);

    /** 목록용 경량 조회: 긴 본문(design_text/change_summary)은 제외 — 대시보드가 15초마다 폴링한다. */
    @Select("""
            SELECT id, project_path, issue_number, issue_title, issue_url, default_branch, state,
                   claude_session_id, branch_name, NULL AS design_text, NULL AS change_summary, commit_sha, pr_url, last_error,
                   feedback_rounds, verify_rounds, NULL AS verify_result, work_type, base_branch, created_at, updated_at
            FROM issue_session ORDER BY updated_at DESC LIMIT #{limit}
            """)
    List<IssueSession> recentLight(@Param("limit") int limit);
}
