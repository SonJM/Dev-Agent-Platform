package io.github.lib0823.devagent.gitlab;

import io.github.lib0823.devagent.agent.CodingAgent;
import io.github.lib0823.devagent.config.GitLabProperties;
import io.github.lib0823.devagent.messenger.MessengerService;
import io.github.lib0823.devagent.notify.NotificationService;
import io.github.lib0823.devagent.ops.AlertService;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import static io.github.lib0823.devagent.gitlab.IssueTexts.*;

/**
 * 이슈 에이전트의 자기방 알림: 긴 메시지 분할 전송, 사용량 한도 알림(한도 창당 1회).
 * {@link IssueAgentService} 에서 분리.
 */
@Component
public class IssueNotifier {

    private static final Logger log = LoggerFactory.getLogger(IssueNotifier.class);
    private static final String PFX = "[이슈] ";

    private final GitLabProperties props;
    private final MessengerService client;
    private final AlertService alert;
    private final io.github.lib0823.devagent.llm.UsageService usage;
    private final NotificationService notifications;

    public IssueNotifier(GitLabProperties props, MessengerService client, AlertService alert,
                         io.github.lib0823.devagent.llm.UsageService usage, NotificationService notifications) {
        this.notifications = notifications;
        this.props = props;
        this.client = client;
        this.alert = alert;
        this.usage = usage;
    }

    /** 마지막으로 자기방에 알린 한도 리셋 시각(같은 한도 창에 한 번만 알림). */
    private volatile java.time.Instant limitNotified;

    /** 사용량 한도 알림(같은 리셋 시각에 대해 한 번만). 새 분석 시작은 {@code usage.isLimited()} 로 리셋까지 보류된다. */
    public void notifyLimit(CodingAgent.UsageLimitException e) {
        if (e.until().equals(limitNotified)) {
            return;
        }
        limitNotified = e.until();
        String until = usage.limitUntilText();
        sendSelf(PFX + "⚠ Claude 사용량 한도에 도달했습니다 (리셋 " + until + "). 그때까지 새 분석을 시작하지 않습니다. "
                + "진행 중이던 수정은 변경을 보존한 채 리뷰 대기로 넘깁니다(리셋 후 「피드백: 이어서 진행」). 큐의 이슈는 리셋 후 자동으로 이어집니다.");
        alert.alert("Claude 사용량 한도 (리셋 " + until + ")", e);
    }

    /** 자기방 전송(길면 분할). 전송 실패는 로그만(상태 전이는 이미 DB 에 저장됨). */
    public void sendSelf(String text) {
        notifications.addIssue(text);   // 화면 알림함(메신저와 무관하게 남는다)
        if (!client.notifyOn(MessengerService.NOTICE_ISSUE)) {
            return;                     // 메신저 알림을 껐다: 확인·진행은 에이전트 화면과 알림함에서
        }
        int max = Math.max(500, props.agent().maxMessageChars());
        List<String> parts = new ArrayList<>();
        String rest = text;
        while (rest.length() > max) {
            int cut = rest.lastIndexOf('\n', max);
            if (cut < max / 2) {
                cut = max;
            }
            parts.add(rest.substring(0, cut));
            rest = rest.substring(cut).replaceFirst("^\n", "");
        }
        parts.add(rest);
        for (int i = 0; i < parts.size(); i++) {
            String p = parts.get(i);
            if (i > 0 && !p.startsWith(PFX)) {
                p = PFX + "(계속 " + (i + 1) + "/" + parts.size() + ")\n" + p;
            }
            try {
                client.connect();
                client.sendToSelf(p);
            } catch (RuntimeException e) {
                log.warn("이슈 에이전트 자기방 전송 실패: {}", e.toString());
            }
        }
    }
}
