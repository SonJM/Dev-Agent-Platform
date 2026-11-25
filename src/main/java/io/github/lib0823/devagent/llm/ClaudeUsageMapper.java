package io.github.lib0823.devagent.llm;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Claude 호출 1건당 토큰 사용량 기록(claude_usage). 구독제라 비용 대신 토큰으로 사용량을 본다.
 * source: agent(이슈 에이전트). 예전 행의 ops·chat 출처는 그대로 남지만 새로 생기지 않는다. limit_hit: 이 호출에서 사용량 한도 메시지를 봤는지.
 */
@Mapper
public interface ClaudeUsageMapper {

    @Update("""
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
            )
            """)
    void ensureTable();

    @Update("CREATE INDEX IF NOT EXISTS idx_claude_usage_at ON claude_usage (at)")
    void ensureIndex();

    @Insert("""
            INSERT INTO claude_usage (source, stage, model, ref, input_tokens, output_tokens, cache_read, cache_create, limit_hit)
            VALUES (#{source}, #{stage}, #{model}, #{ref}, #{input}, #{output}, #{cacheRead}, #{cacheCreate}, #{limitHit})
            """)
    void insert(@Param("source") String source, @Param("stage") String stage, @Param("model") String model, @Param("ref") String ref,
                @Param("input") long input, @Param("output") long output, @Param("cacheRead") long cacheRead,
                @Param("cacheCreate") long cacheCreate, @Param("limitHit") boolean limitHit);

    /** since 이후 호출 전부(오래된 순). 7일치라도 수백 건이라 Java 에서 집계한다. */
    @Select("""
            SELECT at, source, stage, model, input_tokens, output_tokens, cache_read, cache_create, limit_hit
            FROM claude_usage WHERE at >= #{since} ORDER BY at
            """)
    List<Map<String, Object>> since(@Param("since") OffsetDateTime since);
}
