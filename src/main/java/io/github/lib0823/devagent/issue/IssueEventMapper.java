package io.github.lib0823.devagent.issue;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface IssueEventMapper {

    /** 기존 DB 에도 자동 추가되도록 기동 시 멱등 생성. (db/schema.sql 과 동일 정의) */
    @Update("""
            CREATE TABLE IF NOT EXISTS issue_event (
                id          BIGSERIAL    PRIMARY KEY,
                session_id  BIGINT       NOT NULL,
                kind        VARCHAR(16)  NOT NULL,
                message     TEXT,
                detail      TEXT,
                created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
            )
            """)
    void ensureTable();

    /** 옛 kind 값(T-011 이전)을 새 값으로 바꾼다(멱등). */
    @Update("UPDATE issue_event SET kind = 'REMOTE' WHERE kind = 'GITLAB'")
    void renameLegacyKind();

    @Update("CREATE INDEX IF NOT EXISTS idx_issue_event_session ON issue_event (session_id, id)")
    void ensureIndex();

    @Insert("INSERT INTO issue_event (session_id, kind, message, detail) VALUES (#{sessionId}, #{kind}, #{message}, #{detail})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(IssueEvent e);

    @Select("""
            SELECT id, session_id, kind, message, detail, created_at FROM issue_event
            WHERE session_id = #{sessionId} AND id > #{afterId}
            ORDER BY id ASC LIMIT #{limit}
            """)
    List<IssueEvent> listAfter(@Param("sessionId") long sessionId, @Param("afterId") long afterId, @Param("limit") int limit);

    /** 목록용 경량 조회: detail 제외 + 존재 여부만. 도구 결과/프롬프트 본문이 커서 세션당 수 MB 가 될 수 있다. */
    @Select("""
            SELECT id, session_id, kind, message, NULL AS detail,
                   (detail IS NOT NULL AND detail <> '') AS has_detail, created_at
            FROM issue_event WHERE session_id = #{sessionId} AND id > #{afterId}
            ORDER BY id ASC LIMIT #{limit}
            """)
    List<IssueEvent> listAfterLight(@Param("sessionId") long sessionId, @Param("afterId") long afterId, @Param("limit") int limit);

    /** 여러 세션(질의 스레드의 항목들)의 이벤트를 id 순으로 한 번에. 경량 조회. */
    @Select("""
            <script>
            SELECT id, session_id, kind, message, NULL AS detail,
                   (detail IS NOT NULL AND detail != '') AS has_detail, created_at
            FROM issue_event WHERE id > #{afterId} AND session_id IN
            <foreach collection="sessionIds" item="s" open="(" separator="," close=")">#{s}</foreach>
            ORDER BY id ASC LIMIT #{limit}
            </script>
            """)
    List<IssueEvent> listAfterLightIn(@Param("sessionIds") List<Long> sessionIds, @Param("afterId") long afterId, @Param("limit") int limit);

    @Select("SELECT id, session_id, kind, message, detail, created_at FROM issue_event WHERE id = #{id}")
    IssueEvent findOne(@Param("id") long id);

    @Select("SELECT COALESCE(MAX(id), 0) FROM issue_event WHERE session_id = #{sessionId}")
    long lastId(@Param("sessionId") long sessionId);

    /** 세션 재시작 시 이전 라운드 이벤트 정리. */
    @Update("DELETE FROM issue_event WHERE session_id = #{sessionId}")
    int deleteBySession(@Param("sessionId") long sessionId);
}
