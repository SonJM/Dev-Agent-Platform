package io.github.lib0823.devagent.gitlab;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 이슈 에이전트의 순수 함수 모음(상태·의존성 없음): 자기방 답변 해석, 작업 유형·일감번호·커밋 제목 규칙,
 * 보고서 절 추출, 참고 저장소 표기 해석, 문자열 도우미. {@link IssueAgentService} 와 협력 객체들이 static import 로 쓴다.
 */
public final class IssueTexts {

    /** 스캔 라벨이 설정돼 있는지. 비면 열린 이슈 전부가 대상이 되므로 스캔하지 않는다. */
    static boolean hasScanLabel(String label) {
        return label != null && !label.isBlank();
    }


    private IssueTexts() {
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(IssueTexts.class);

    static final Set<String> APPROVE = Set.of(
            "진행", "진행해", "진행해줘", "진행해주세요", "진행하자", "진행할게", "승인", "승인해", "승인해줘", "승인합니다",
            "ok", "okay", "네", "예", "응", "좋아", "좋아요", "좋습니다", "go", "확인", "그대로", "그대로진행", "그대로진행해",
            "yes", "y", "lgtm", "ㅇㅋ", "ㄱㄱ", "고", "푸시", "푸시해", "푸쉬", "커밋", "커밋해", "적용", "적용해", "반영", "반영해");
    static final Set<String> ABORT = Set.of(
            "중단", "중단해", "취소", "취소해", "보류", "보류해", "스킵", "skip", "stop", "그만", "중지", "패스", "pass",
            "안해", "하지마", "하지마세요", "abort", "cancel");
    static final Set<String> STATUS = Set.of("상태", "status", "진행상황", "현황");
    static final Set<String> DIFF = Set.of("diff", "디프", "변경내용", "변경내용보여줘", "코드보여줘", "diff보여줘");
    /** 자기방에서 피드백 문장을 보낼 때 붙이는 접두어(대시보드 입력은 접두어 없이 전부 피드백). */
    static final List<String> FEEDBACK_PREFIX = List.of("피드백:", "피드백 :", "피드백：", "피드백 ", "fb:", "fb ", "feedback:", "feedback ");

    /** 리뷰 대기에서 코드 수정 없이 검증(봇 검사+독립 리뷰)만 다시 돌리는 명령. "검증만 한번 더 부탁해" 같은 문장도 인정한다. */
    private static final Pattern VERIFY_ONLY = Pattern.compile("(재검증|검증|verify|recheck|리뷰만)");

    private static final Pattern CHANGE_WORDS = Pattern.compile("(수정|고쳐|고치|바꿔|바꾸|변경|추가|삭제|제거|구현|반영|적용|리팩터|리팩토|rename|fix|change|add|remove)");

    /** "검증만 한번 더", "재검증", "다시 검증 부탁" 처럼 검증 재실행만 요구하는 짧은 문장인지(수정을 뜻하는 말이 섞이면 피드백). */
    static boolean isVerifyOnly(String text) {
        String t = text == null ? "" : text.strip();
        if (t.isEmpty() || t.length() > 60) {
            return false;
        }
        String lower = t.toLowerCase(Locale.ROOT);
        return VERIFY_ONLY.matcher(lower).find() && !CHANGE_WORDS.matcher(lower).find();
    }

    /**
     * 메시지 앞의 세션 참조를 떼어 낸다: "#1 진행", "shop#1 피드백: …", "acme/shop#1 중단".
     * 반환 [세션id, 나머지]. 참조가 없거나 대기 세션과 안 맞으면 null.
     */
    static String[] splitSessionRef(String text, List<IssueSession> waiting) {
        if (text == null) {
            return null;
        }
        Matcher m = Pattern.compile("^\\s*([A-Za-z0-9_.\\-/]*)#(\\d+)\\s*[:：,]?\\s*(.*)$", Pattern.DOTALL).matcher(text);
        if (!m.find()) {
            return null;
        }
        String path = m.group(1).toLowerCase(Locale.ROOT);
        long iid = Long.parseLong(m.group(2));
        List<IssueSession> hits = waiting.stream().filter(w -> w.getIssueIid() == iid
                && (path.isEmpty() || w.getProjectPath().toLowerCase(Locale.ROOT).equals(path) || w.getProjectPath().toLowerCase(Locale.ROOT).endsWith("/" + path))).toList();
        if (hits.size() != 1) {
            return null;                                   // 같은 번호의 이슈가 두 프로젝트에 대기 중이면 프로젝트까지 써야 한다
        }
        return new String[] {String.valueOf(hits.get(0).getId()), m.group(3).strip()};
    }

    /**
     * 자기방 메시지를 답변으로 해석한다. 인정: 명령 키워드(진행/승인/중단/diff/상태…), 「이슈 …」 명령, 「피드백: 문장」(접두어 뒤 문장을 피드백으로).
     * 그 외(스티커, 잡담, 접두어 없는 문장)는 null — 호출자가 무시한다.
     */
    static String parseSelfReply(String text) {
        String t = text == null ? "" : text.strip();
        if (t.isEmpty()) {
            return null;
        }
        String key = norm(t);
        if (APPROVE.contains(key) || ABORT.contains(key) || STATUS.contains(key) || DIFF.contains(key)) {
            return t;
        }
        if (t.equals("이슈") || t.startsWith("이슈 ")) {
            return t;
        }
        if (isVerifyOnly(t)) {
            return t;                                 // 「검증」「재검증」「검증만 한번 더」 — 접두어 없이 명령으로 인정
        }
        String lower = t.toLowerCase(Locale.ROOT);
        for (String p : FEEDBACK_PREFIX) {
            if (lower.startsWith(p)) {
                String body = t.substring(p.length()).strip();
                return body.isEmpty() ? null : body;
            }
        }
        return null;
    }

    /** 스티커("(충격_우람)")나 짧은 추임새가 아니라 사람이 쓴 문장처럼 보이는지 — 무시 안내를 보낼지 결정. */
    static boolean looksLikeSentence(String text) {
        String t = text == null ? "" : text.strip();
        if (t.length() < 8) {
            return false;
        }
        if (t.startsWith("(") && t.endsWith(")") && !t.contains(" ")) {
            return false;   // 메신저 스티커 표기
        }
        return t.chars().filter(Character::isLetterOrDigit).count() >= 6;
    }

    /** "## 작업 유형" 절의 첫 단어(feature/fix/refactor/doc/hotfix, 한글 표기도 허용). 없으면 null. */
    static String parseWorkType(String design, List<String> allowed) {
        if (design == null) {
            return null;
        }
        String sec = section(design, "작업 유형");
        if (sec.equals(design.strip())) {
            return null;                                   // 절 없음
        }
        String first = sec.lines().map(String::strip).filter(l -> !l.isEmpty()).findFirst().orElse("").toLowerCase(Locale.ROOT);
        Map<String, String> alias = Map.of("기능", "feature", "feat", "feature", "버그", "fix", "수정", "fix", "리팩토링", "refactor", "리팩터링", "refactor",
                "문서", "doc", "docs", "doc", "긴급", "hotfix", "핫픽스", "hotfix");
        for (String token : first.split("[^a-z0-9가-힣]+")) {
            if (token.isEmpty()) {
                continue;
            }
            String t = alias.getOrDefault(token, token);
            if (allowed.contains(t)) {
                return t;
            }
        }
        return null;
    }

    /**
     * 푸시할 브랜치 이름 결정: 원격에 같은 이름이 있으면(사람이 쓰는 중이거나 재실행) 덮지 않고 {@code -ai}, 그것도 있으면
     * {@code -ai-r<MMddHHmm>} 으로 구분한다.
     */
    static String uniqueBranch(String branch, java.util.function.Predicate<String> remoteExists, java.time.LocalDateTime now) {
        if (!remoteExists.test(branch)) {
            return branch;
        }
        String alt = branch + "-ai";
        return remoteExists.test(alt) ? alt + "-r" + now.format(java.time.format.DateTimeFormatter.ofPattern("MMddHHmm")) : alt;
    }

    /** 커밋 첫 줄을 컨벤션(feat|fix|docs|style|refactor|test|chore: 제목)으로. 타입이 없으면 작업 유형에서 유추해 붙인다. */
    static String conventionalTitle(String title, String workType) {
        String t = title == null ? "" : title.strip();
        if (t.matches("(?i)^(feat|fix|docs|style|refactor|test|chore)(\\([^)]*\\))?!?:\\s.*")) {
            return t;
        }
        t = t.replaceFirst("(?i)^(feat|fix|docs|style|refactor|test|chore)(\\([^)]*\\))?\\s*[:：]\\s*", "");
        String type = switch (workType == null ? "" : workType) {
            case "feature" -> "feat";
            case "refactor" -> "refactor";
            case "doc" -> "docs";
            default -> "fix";
        };
        return type + ": " + t;
    }

    /**
     * 커밋 메시지에 이 이슈를 닫는 GitHub 키워드(Closes/Fixes/Resolves/Implements #n)가 있는지.
     * 번호만 언급했거나("Refs #1") 다른 번호의 앞부분과 겹치는 경우("#12" 안의 "#1")는 닫는 것으로 치지 않는다.
     */
    static boolean closesIssue(String message, long iid) {
        return message != null && Pattern.compile("(?i)\\b(clos(e[sd]?|ing)|fix(e[sd]|ing)?|resolv(e[sd]?|ing)|implement(s|ed|ing)?):?\\s+#" + iid + "(?!\\d)")
                .matcher(message).find();
    }

    /** 피드백 문장에서 "작업 유형: xxx" / "유형: xxx" 부분만 뽑는다(없으면 빈 문자열). */
    static String feedbackTypeLine(String feedback) {
        if (feedback == null) {
            return "";
        }
        Matcher m = Pattern.compile("(?:작업\\s*)?유형\\s*[:：]\\s*([A-Za-z가-힣]+)").matcher(feedback);
        return m.find() ? m.group(1) : "";
    }

    static String lastLine(String s) {
        if (s == null || s.isBlank()) {
            return "(변경 없음)";
        }
        String[] lines = s.strip().split("\n");
        return lines[lines.length - 1].strip();
    }

    /**
     * 이슈 본문의 "참고 저장소" 절에서 {@code owner/repo} (+ 브랜치) 를 뽑는다. 브랜치 표기는 {@code `owner/repo` **main**},
     * {@code owner/repo@main}, {@code owner/repo main 브랜치} 를 받고, 없으면 null(프로젝트 기본 브랜치).
     * 절 제목은 "참고 저장소" 또는 "reference repo" 를 포함한 마크다운 헤더이고, 다음 헤더 전까지를 본다.
     */
    static List<String[]> parseReferenceRepos(String description) {
        List<String[]> out = new ArrayList<>();
        if (description == null || description.isBlank()) {
            return out;
        }
        String[] lines = description.split("\\r?\\n");
        boolean in = false;
        Pattern head = Pattern.compile("^\\s*#{1,6}\\s*(.*)$");
        Pattern repo = Pattern.compile("`?([A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)+)`?"
                + "(?:\\s*(?:@([A-Za-z0-9_./-]+)|\\*\\*([A-Za-z0-9_./-]+)\\*\\*|([A-Za-z0-9_./-]+)\\s*브랜치))?");
        for (String line : lines) {
            Matcher h = head.matcher(line);
            if (h.matches()) {
                String t = h.group(1).toLowerCase(Locale.ROOT);
                in = t.contains("참고 저장소") || t.contains("참고저장소") || t.contains("reference repo");
                continue;
            }
            if (!in) {
                continue;
            }
            Matcher m = repo.matcher(line);
            while (m.find()) {
                String path = m.group(1);
                if (path.contains("..") || path.endsWith(".")) {
                    continue;                         // `resources/.../dsl` 같은 생략 표기
                }
                if (path.matches(".*\\.(java|md|xml|yml|yaml|jsp|js|gradle|kt|properties)$") || path.startsWith("src/")
                        || path.startsWith("docs/") || path.startsWith("jobs/") || path.startsWith("http")) {
                    continue;                         // 파일 경로·URL 은 저장소가 아니다
                }
                String branch = m.group(2) != null ? m.group(2) : m.group(3) != null ? m.group(3) : m.group(4);
                if (out.stream().noneMatch(r -> r[0].equals(path))) {
                    out.add(new String[]{path, branch});
                }
            }
        }
        return out;
    }

    static String askDesign() {
        return "👉 답변: 「진행」(설계대로 수정) / 「중단」 / 「피드백: …」(설계에 반영) — 그 외 메시지는 무시";
    }

    static String askReview() {
        return "👉 답변: 「진행」(브랜치 생성·커밋·푸시) / 「검증」(수정 없이 검증만 다시) / 「diff」(전체 diff 보기) / 「중단」 / 「피드백: …」(추가 수정) — 그 외 메시지는 무시";
    }

    /** 에이전트가 쓴 진짜 보고인지("## 변경 요약" 절이 있고 중단 안내만 있는 게 아닌지). */
    static boolean hasRealSummary(String summary) {
        if (summary == null || summary.isBlank()) {
            return false;
        }
        Matcher m = Pattern.compile("(?ms)^##\\s*변경 요약\\s*$\\n?(.*?)(?=^##\\s|\\z)").matcher(summary);
        if (!m.find()) {
            return false;
        }
        String body = m.group(1).strip();
        return !body.isEmpty() && !body.startsWith("⏱");
    }

    /** 작업 유형 → 커밋 타입(feature→feat, doc→docs, refactor→refactor, 그 외 fix). */
    static String commitTypeFor(String workType) {
        return switch (workType == null ? "" : workType) {
            case "feature" -> "feat";
            case "refactor" -> "refactor";
            case "doc" -> "docs";
            default -> "fix";
        };
    }

    /** "## 제목" 절 본문 추출(없으면 전체 텍스트). */
    static String section(String text, String title) {
        if (text == null) {
            return "";
        }
        Matcher m = Pattern.compile("(?ms)^##\\s*" + Pattern.quote(title) + "\\s*$\\n?(.*?)(?=^##\\s|\\z)").matcher(text);
        return m.find() ? m.group(1).strip() : text.strip();
    }

    static String firstLine(String s) {
        int i = s.indexOf('\n');
        return (i < 0 ? s : s.substring(0, i)).strip();
    }

    /** 의도 비교용 정규화: 소문자, 공백/구두점 제거. */
    static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[\\s.!~?,。、\"'`「」]+", "");
    }

    static String abbreviate(String s, int n) {
        return s == null ? "" : (s.length() > n ? s.substring(0, n) + "…" : s);
    }
}
