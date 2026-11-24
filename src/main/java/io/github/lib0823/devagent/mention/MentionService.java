package io.github.lib0823.devagent.mention;

import io.github.lib0823.devagent.collect.ChatMessageMapper;
import io.github.lib0823.devagent.collect.ChatMessageRow;
import io.github.lib0823.devagent.admin.SettingService;
import io.github.lib0823.devagent.collect.RoomCursor;
import io.github.lib0823.devagent.collect.RoomCursorMapper;
import io.github.lib0823.devagent.config.BotProperties;
import io.github.lib0823.devagent.config.DocsProperties;
import io.github.lib0823.devagent.docs.KnowledgeSource;
import io.github.lib0823.devagent.mcp.OpsQueryService;
import io.github.lib0823.devagent.messenger.MessengerService;
import io.github.lib0823.devagent.llm.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;

/**
 * 멘션 자동응답 (DRAFT). 미처리 멘션을 찾아 초안을 생성해 "나에게 보내기" 로 전달한다.
 * 안전정책: 중복(mention_reply PK)·쿨다운·루프(봇/내 메시지)·민감요청 보류.
 */
@Service
public class MentionService {

    private static final Logger log = LoggerFactory.getLogger(MentionService.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String HOLDING = "확인 후 답드리겠습니다.";

    private static final String SYSTEM = """
            당신은 사내 메신저에서 이 계정의 사용자를 대신해 멘션에 답할 초안을 작성합니다.
            아래 방의 최근 대화 맥락과 '멘션 메시지'를 보고, 사용자 입장에서 보낼 자연스러운 한국어 답장을 1~3문장으로 작성하세요.
            정보가 부족하거나 불확실하면 단정하지 말고 되묻거나 확인하는 톤으로 쓰세요.
            답장 '본문만' 출력하세요 — 접두어, 설명, 따옴표 없이.
            """;

    private final MessengerService client;
    private final ChatModel claude;
    private final ChatMessageMapper messageMapper;
    private final MentionReplyMapper replyMapper;
    private final RoomCursorMapper cursorMapper;
    private final BotProperties bot;
    private final SettingService settings;
    private final KnowledgeSource docs;
    private final DocsProperties docsProps;
    private final OpsQueryService ops;

    public MentionService(MessengerService client, ChatModel claude, ChatMessageMapper messageMapper,
                          MentionReplyMapper replyMapper, RoomCursorMapper cursorMapper,
                          BotProperties bot, SettingService settings,
                          KnowledgeSource docs, DocsProperties docsProps,
                          OpsQueryService ops) {
        this.client = client;
        this.claude = claude;
        this.messageMapper = messageMapper;
        this.replyMapper = replyMapper;
        this.cursorMapper = cursorMapper;
        this.bot = bot;
        this.settings = settings;
        this.docs = docs;
        this.docsProps = docsProps;
        this.ops = ops;
    }

    public void process() {
        List<ChatMessageRow> candidates = messageMapper.selectUnrepliedMentions(100);
        if (candidates.isEmpty()) {
            return;
        }
        log.info("멘션 처리 — 후보 {}건", candidates.size());
        for (ChatMessageRow c : candidates) {
            try {
                processOne(c);
            } catch (Exception e) {
                log.warn("멘션 처리 실패 messageId={}: {}", c.getMessageId(), e.toString());
            }
        }
    }

    private void processOne(ChatMessageRow c) {
        boolean command = isCommand(c.getBody());

        // @AI 명령은 내 메시지여도 처리. 그 외 '이름 멘션'은 내 메시지면 무시(루프 방지).
        if (!command && !bot.mention().allowSelfSender()
                && c.getSenderId() != null && c.getSenderId().equals(client.selfId())) {
            record(c, "SKIPPED", null, "own-message");
            return;
        }

        if (command) {
            handleCommand(c);
            return;
        }

        // 빈도 제한
        OffsetDateTime last = replyMapper.selectLastCreatedAt(c.getRoomId());
        if (last != null && last.isAfter(OffsetDateTime.now().minusMinutes(bot.mention().cooldownMinutes()))) {
            record(c, "SKIPPED", null, "cooldown");
            log.debug("멘션 skip(cooldown) room={}", c.getRoomId());
            return;
        }
        // 민감 요청 → 보류 초안
        if (isSensitive(c.getBody())) {
            deliverDraft(c, HOLDING, true);
            record(c, "DRAFT", HOLDING, "sensitive");
            log.info("멘션 민감요청 보류 → 초안 전달 messageId={}", c.getMessageId());
            return;
        }
        // 일반 초안 생성
        List<ChatMessageRow> ctx = messageMapper.selectContext(
                c.getRoomId(), c.getRoomType(), c.getSentAt(), bot.mention().contextSize());
        Collections.reverse(ctx); // 과거→현재
        // 멘션 RAG: 질문이 사내문서와 강하게 관련될 때만(게이트 통과) 참고. 그 외엔 평소대로.
        String docContext = docsProps.useInMentions() ? docs.retrieveContext(c.getBody()) : null;
        String draft = claude.complete(buildPrompt(roomName(c), ctx, c, docContext));

        if (isAutoRoom(c)) {
            // 화이트리스트 방: 실제 회신 (MessengerService 가 한 번 더 허용목록 검사)
            client.sendAutoReply(c.getRoomId(), c.getRoomType(), draft);
            record(c, "AUTO", draft, null);
            log.info("멘션 AUTO 회신 messageId={} room={}:{}", c.getMessageId(), c.getRoomType(), c.getRoomId());
        } else {
            deliverDraft(c, draft, false);
            record(c, "DRAFT", draft, null);
            log.info("멘션 초안 생성·전달(DRAFT) messageId={}", c.getMessageId());
        }
    }

    private boolean isAutoRoom(ChatMessageRow c) {
        return settings.getAutoRoomKeys().contains(c.getRoomType() + ":" + c.getRoomId());
    }

    private boolean isSensitive(String body) {
        if (body == null) {
            return false;
        }
        List<String> kws = bot.mention().sensitiveKeywords();
        if (kws == null) {
            return false;
        }
        return kws.stream().anyMatch(body::contains);
    }

    private void deliverDraft(ChatMessageRow c, String draft, boolean sensitive) {
        String header = "[멘션 초안] " + roomName(c) + " / " + nvl(c.getSenderName(), "?")
                + (sensitive ? " (민감요청 보류)" : "");
        client.notifySelf(MessengerService.NOTICE_MENTION, header + "\n> " + oneLine(c.getBody()) + "\n---\n" + draft);
    }

    private void record(ChatMessageRow c, String mode, String replyText, String skipReason) {
        MentionReply r = new MentionReply();
        r.setMessageSeq(c.getSeq());
        r.setRoomId(c.getRoomId());
        r.setMode(mode);
        r.setReplyText(replyText);
        r.setSkipReason(skipReason);
        replyMapper.insertIgnore(r);
    }

    private boolean isCommand(String body) {
        String cmd = bot.mention().commandKeyword();
        return body != null && cmd != null && !cmd.isBlank() && body.contains(cmd);
    }

    /** @AI 직접 호출 처리: 요청을 수행해 같은 방(self/화이트리스트)에 답, 아니면 자기방으로. */
    private void handleCommand(ChatMessageRow c) {
        boolean self = "self".equalsIgnoreCase(c.getRoomType());
        String rest = stripCommand(c.getBody());

        // 「이슈 …」·「질문 …」 명령과 확인 답변은 메신저 명령 라우터(messenger/MessengerCommandRouter)가 받는다 — 여기서 중복 처리하지 않는다(T-006)
        // "@AI 운영 …" / 운영 키워드 → 운영 MCP 질의. 자기방 + '방에 직접 답' 화이트리스트 방에서만(그 외 방엔 클러스터/DB 정보를 노출하지 않는다)
        String opsRoomKey = c.getRoomType() + ":" + c.getRoomId();
        boolean opsRoom = self || (ops.allowAutoRooms() && settings.getAutoRoomKeys().contains(opsRoomKey));
        if (opsRoom && ops.isOpsQuery(rest)) {
            handleOpsQuery(c, ops.stripPrefix(rest), !self);
            return;
        }

        List<ChatMessageRow> ctx = messageMapper.selectContext(
                c.getRoomId(), c.getRoomType(), c.getSentAt(), bot.mention().contextSize());
        Collections.reverse(ctx);
        // RAG 게이트: 질문과 관련된 사내문서가 있으면 컨텍스트로 주입, 없으면 null(평소 속도 유지)
        String docContext = docs.retrieveContext(c.getBody());

        String roomKey = c.getRoomType() + ":" + c.getRoomId();
        boolean canSendInRoom = "self".equalsIgnoreCase(c.getRoomType())
                || settings.getAutoRoomKeys().contains(roomKey);

        // 느린 턴(문서 참고) 안내 선전송 — 기본 off, 방에 답하는 경우만
        if (bot.mention().progressAck() && canSendInRoom && docContext != null) {
            try {
                client.sendToRoomChecked(c.getRoomId(), c.getRoomType(), "[AI] 관련 문서 확인 중… 잠시만요.");
            } catch (RuntimeException e) {
                log.debug("생성중 안내 전송 실패(무시): {}", e.toString());
            }
        }

        String answer = claude.complete(buildCommandPrompt(roomName(c), ctx, c, docContext));
        if (canSendInRoom) {
            client.sendToRoomChecked(c.getRoomId(), c.getRoomType(), "[AI] " + answer);
            record(c, "AI", answer, null);
            log.info("AI 명령 응답(방) messageId={} room={}", c.getMessageId(), roomKey);
        } else {
            client.sendToSelf("[AI] (요청 방: " + roomName(c) + ")\n" + answer);
            record(c, "AI", answer, "room-not-allowed");
            log.info("AI 명령 응답(자기방) messageId={} room={}", c.getMessageId(), roomKey);
        }
    }

    /** 운영 MCP 질의: one-shot claude(MCP) 로 답해 자기방으로. 수 초~수십 초 걸리므로 접수 안내를 먼저 보낸다. */
    private void handleOpsQuery(ChatMessageRow c, String question, boolean inRoom) {
        if (question.isBlank()) {
            sendOps(c, inRoom, "[AI] 운영 조회 사용법: @AI 운영 <질문>  예) @AI 운영 k3s 노드 상태 / 오늘 analyze 인덱스 문서 수 / DB 커넥션 상태");
            record(c, "OPS", null, "empty");
            return;
        }
        try {
            sendOps(c, inRoom, "[AI] 운영 데이터 조회 중… (" + abbreviateText(question, 60) + ")");
        } catch (RuntimeException e) {
            log.debug("운영 조회 안내 전송 실패(무시): {}", e.toString());
        }
        List<ChatMessageRow> ctx = messageMapper.selectContext(
                c.getRoomId(), c.getRoomType(), c.getSentAt(), Math.min(6, bot.mention().contextSize()));
        Collections.reverse(ctx);
        StringBuilder context = new StringBuilder("--- 자기방 최근 대화(맥락 참고용) ---\n");
        for (ChatMessageRow m : ctx) {
            context.append(line(m)).append('\n');
        }
        OpsQueryService.Answer a = ops.ask(question, context.toString());
        String footer = a.error() ? "" : String.format("\n— 운영 MCP 조회 · %d턴 · %ds", a.turns(), a.elapsedMs() / 1000);
        sendOps(c, inRoom, "[AI] " + a.text() + footer);
        record(c, "OPS", a.text(), a.error() ? "error" : null);
        log.info("운영 MCP 질의 응답 messageId={} room={}:{} inRoom={} error={} {}ms", c.getMessageId(), c.getRoomType(), c.getRoomId(),
                inRoom, a.error(), a.elapsedMs());
    }

    /** 운영 질의 응답 전송: 화이트리스트 방이면 그 방(MessengerService 가 허용목록을 한 번 더 검사), 아니면 자기방. */
    private void sendOps(ChatMessageRow c, boolean inRoom, String text) {
        if (inRoom) {
            client.sendToRoomChecked(c.getRoomId(), c.getRoomType(), text);
        } else {
            client.sendToSelf(text);
        }
    }

    private static String abbreviateText(String s, int n) {
        return s == null ? "" : (s.length() > n ? s.substring(0, n) + "…" : s);
    }

    private String stripCommand(String body) {
        String t = body == null ? "" : body.strip();
        String cmd = bot.mention().commandKeyword();
        if (cmd != null && !cmd.isBlank()) {
            t = t.replace(cmd, " ").strip();
        }
        return t;
    }

    private String buildCommandPrompt(String roomName, List<ChatMessageRow> ctx, ChatMessageRow cmd, String docContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                사용자가 메신저에서 AI 비서를 직접 호출했습니다. 아래 '요청'을 수행해 한국어로 간결하고 바로 쓸 수 있게 답하세요.
                호출 키워드(@AI 등)는 무시하고, 방의 최근 대화가 맥락으로 필요하면 참고하세요. 불확실하면 가정을 밝히고 답하세요.
                접두어나 메타설명 없이 본문만 출력하세요.
                """);
        if (docContext != null && !docContext.isBlank()) {
            sb.append('\n').append(docContext);
        }
        sb.append("\n--- '").append(roomName).append("' 최근 대화 ---\n");
        for (ChatMessageRow m : ctx) {
            sb.append(line(m)).append('\n');
        }
        sb.append("\n--- 요청 ---\n").append(line(cmd)).append('\n');
        return sb.toString();
    }

    private String buildPrompt(String roomName, List<ChatMessageRow> ctx, ChatMessageRow mention, String docContext) {
        StringBuilder sb = new StringBuilder();
        sb.append(SYSTEM);
        if (docContext != null && !docContext.isBlank()) {
            sb.append("\n아래 사내문서에 답이 있으면 바로 발췌해 참고하되, 깊이 추론하지 말고 자연스러운 1~3문장으로만 답하고 필요하면 출처 URL을 덧붙이세요.\n")
              .append(docContext);
        }
        sb.append("\n--- '").append(roomName).append("' 최근 대화 ---\n");
        for (ChatMessageRow m : ctx) {
            sb.append(line(m)).append('\n');
        }
        sb.append("\n--- 멘션 메시지 (이 메시지에 답할 것) ---\n").append(line(mention)).append('\n');
        return sb.toString();
    }

    private String line(ChatMessageRow m) {
        String time = m.getSentAt() == null ? "--" : m.getSentAt().atZoneSameInstant(KST).format(TS);
        return "[" + time + "] " + nvl(m.getSenderName(), "?") + ": " + nvl(m.getBody(), "");
    }

    private String roomName(ChatMessageRow c) {
        RoomCursor cur = cursorMapper.find(c.getRoomId(), c.getRoomType());
        if (cur != null && cur.getRoomName() != null) {
            return cur.getRoomName();
        }
        return c.getRoomType() + ":" + c.getRoomId();
    }

    private static String oneLine(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replace("\n", " ");
        return t.length() > 80 ? t.substring(0, 80) + "..." : t;
    }

    private static String nvl(String s, String def) {
        return s == null ? def : s;
    }
}
