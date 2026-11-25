package io.github.lib0823.devagent.gitlab;

import io.github.lib0823.devagent.config.GitLabProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 이슈 에이전트 폴링: (1) 라벨 이슈 스캔, (2) 확인 대기 리마인더(답변 수신은 메신저 명령 라우터가 한다).
 * 실제 작업(분석/수정/푸시)은 {@link IssueAgentService} 의 워커 스레드에서 수행되어 폴링을 막지 않는다.
 */
@Component
public class IssueAgentScheduler {

    private static final Logger log = LoggerFactory.getLogger(IssueAgentScheduler.class);

    private final IssueAgentService service;
    private final GitLabProperties props;

    public IssueAgentScheduler(IssueAgentService service, GitLabProperties props) {
        this.service = service;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${gitlab.scan-interval-ms}", initialDelay = 30000)
    public void scan() {
        if (!props.enabled()) {
            return;
        }
        try {
            service.scan();
        } catch (Exception e) {
            log.error("이슈 스캔 오류: {}", e.toString());
        }
    }

    @Scheduled(fixedDelayString = "${gitlab.reply-poll-interval-ms}", initialDelay = 15000)
    public void replies() {
        if (!props.enabled()) {
            return;
        }
        try {
            service.remindWaiting();
        } catch (Exception e) {
            log.error("확인 대기 리마인더 오류: {}", e.toString());
        }
    }
}
