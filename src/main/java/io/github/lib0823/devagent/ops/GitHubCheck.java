package io.github.lib0823.devagent.ops;

import io.github.lib0823.devagent.config.GitHubProperties;
import io.github.lib0823.devagent.config.IssueProperties;
import io.github.lib0823.devagent.issue.GitHubClient;
import io.github.lib0823.devagent.issue.GitHubException.Kind;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Doctor 의 GitHub 항목: 토큰 유무 → 토큰 유효(401) → 대상 저장소별 읽기 접근 → 토큰 만료일.
 * 호출은 Doctor 의 60초 캐시 안에서만 일어난다. 쓰기 권한은 조회로 확정할 수 없어 "읽기 확인"까지만 표시한다.
 * 토큰 값·Authorization 헤더는 결과에 싣지 않는다(실패 문구는 상태 코드와 GitHub 오류 문구뿐).
 */
final class GitHubCheck {

    /** 토큰 만료가 이 일수 이하로 남으면 warn(90일 토큰의 마지막 2주 — PM 결정 2026-11-25). */
    static final int TOKEN_EXPIRY_WARN_DAYS = 14;

    static final String KEY = "github";
    static final String NAME = "GitHub";

    private static final String TOKEN_HOWTO = "GitHub → Settings → Developer settings → Personal access tokens → Fine-grained tokens 에서 발급하세요"
            + "(저장소 선택은 \"Only select repositories\"로 대상 저장소만, 권한은 Contents·Issues·Pull requests 읽기/쓰기 + Metadata 읽기, "
            + "만료 90일 안팎, 개인 계정 저장소만 — 조직 저장소는 대상이 아닙니다). 값은 서버 .env 의 GITHUB_TOKEN 에 넣고 재시작합니다.";
    private static final String REISSUE = "토큰을 다시 발급해 .env 의 GITHUB_TOKEN 을 고치고 재시작하세요. " + TOKEN_HOWTO;
    private static final String REPO_HINT = "토큰의 저장소 선택에 이 저장소를 더하세요(조직 저장소는 대상이 아닙니다 — 개인 계정 저장소만).";
    private static final DateTimeFormatter EXPIRY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");

    private GitHubCheck() {
    }

    static Map<String, Object> run(IssueProperties agent, GitHubProperties gh, GitHubClient client, List<String> projects, Instant now) {
        if (!agent.enabled()) {
            return Doctor.check(KEY, NAME, "warn", "꺼짐 — 이슈 에이전트·질의를 쓸 수 없습니다",
                    ".env 에 ISSUE_ENABLED=true, GITHUB_TOKEN 을 넣고 재시작하세요. " + TOKEN_HOWTO);
        }
        if (agent.label() == null || agent.label().isBlank()) {
            return Doctor.check(KEY, NAME, "fail", "처리할 이슈 라벨이 비어 있어 스캔하지 않습니다 (ISSUE_LABEL)",
                    ".env 의 ISSUE_LABEL 에 라벨 이름(예: ai-fix)을 넣고 재시작하세요. 라벨이 없으면 열린 이슈 전부가 대상이 되므로 에이전트는 아무것도 처리하지 않습니다.");
        }
        if (!gh.hasToken()) {
            return Doctor.check(KEY, NAME, "fail", "토큰이 비어 있습니다 (GITHUB_TOKEN)", TOKEN_HOWTO);
        }
        List<String> detail = new ArrayList<>();
        List<String> hints = new ArrayList<>();
        Severity sev = new Severity();
        GitHubProperties.ApiUrlPolicy urlPolicy = gh.apiUrlPolicy();
        if (urlPolicy == GitHubProperties.ApiUrlPolicy.INSECURE) {
            return Doctor.check(KEY, NAME, "fail", "GitHub API 주소가 https 가 아닙니다 (GITHUB_API_URL) — 토큰을 보내지 않습니다",
                    ".env 의 GITHUB_API_URL 을 https:// 주소로 고치거나(기본값은 비워 두기) 지운 뒤 재시작하세요.");
        }
        if (urlPolicy == GitHubProperties.ApiUrlPolicy.LOCAL_HTTP) {
            detail.add("API 주소가 로컬 http 입니다(시험용)");
            hints.add("운영에서는 GITHUB_API_URL 을 https 주소로 두세요.");
            sev.warn();
        }
        String expiry = null;

        GitHubClient.Probe user = client.probe("/user");
        if (!user.ok() && user.kind() == Kind.UNAUTHORIZED) {
            return Doctor.check(KEY, NAME, "fail", "토큰이 무효이거나 만료됐습니다 (" + user.message() + ")", REISSUE);
        }
        if (!user.ok() && user.kind() == Kind.RATE_LIMITED) {
            return Doctor.check(KEY, NAME, "warn", "응답 한도에 걸렸습니다 (" + user.message() + ")", "잠시 뒤 다시 점검하세요(한도는 시간이 지나면 풀립니다).");
        }
        if (!user.ok() && user.kind() != Kind.NOT_ACCESSIBLE) {
            return Doctor.check(KEY, NAME, "fail", "GitHub 접속 실패: " + user.message(), "네트워크와 .env 의 GITHUB_API_URL 을 확인하세요.");
        }
        if (user.ok()) {
            expiry = user.tokenExpiration();
        }

        if (projects.isEmpty()) {
            detail.add("대상 저장소 없음");
            hints.add("플랫폼 탭 \"대상 프로젝트\"에서 owner/repo 를 추가하세요.");
            sev.warn();
        } else {
            expiry = probeRepos(client, projects, detail, hints, sev, expiry);
        }
        expiryNote(expiry, now, detail, hints, sev);
        if (sev.level == 0) {
            detail.add("읽기 확인됨 — 쓰기(푸시·PR·코멘트)는 첫 이슈로 확인하세요");
        }
        return Doctor.check(KEY, NAME, sev.name(), String.join(" · ", detail), hints.isEmpty() ? null : String.join(" ", hints));
    }

    /** 저장소별 읽기 접근 확인. 200 이 아닌 것을 이름과 함께 모은다. 만료 헤더를 아직 못 읽었으면 응답에서 읽어 돌려준다. */
    private static String probeRepos(GitHubClient client, List<String> projects, List<String> detail, List<String> hints,
                                     Severity sev, String expiryIn) {
        String expiry = expiryIn;
        List<String> inaccessible = new ArrayList<>();
        List<String> other = new ArrayList<>();
        boolean rateLimited = false;
        int okCount = 0;
        for (String repo : projects) {
            GitHubClient.Probe p = client.probeRepo(repo);
            if (expiry == null && p.tokenExpiration() != null) {
                expiry = p.tokenExpiration();
            }
            if (p.ok()) {
                okCount++;
            } else if (p.kind() == Kind.RATE_LIMITED) {
                rateLimited = true;
                break;
            } else if (p.kind() == Kind.UNAUTHORIZED) {
                other.add(repo + " (" + p.message() + ")");
                hints.add(REISSUE);
            } else if (p.kind() == Kind.NOT_ACCESSIBLE) {
                inaccessible.add(repo);
            } else {
                other.add(repo + " (" + p.message() + ")");
            }
        }
        if (!inaccessible.isEmpty()) {
            detail.add("접근 불가 저장소: " + String.join(", ", inaccessible));
            hints.add(REPO_HINT);
            sev.fail();
        }
        if (!other.isEmpty()) {
            detail.add("조회 실패: " + String.join(", ", other));
            sev.fail();
        }
        if (rateLimited) {
            detail.add("응답 한도에 걸려 일부 저장소를 확인하지 못했습니다");
            hints.add("잠시 뒤 다시 점검하세요.");
            sev.warn();
        }
        if (okCount > 0) {
            detail.add(0, "대상 저장소 " + okCount + "/" + projects.size() + "개 읽기 가능");
        }
        return expiry;
    }

    private static void expiryNote(String header, Instant now, List<String> detail, List<String> hints, Severity sev) {
        if (header == null || header.isBlank()) {
            detail.add("토큰 만료일 없음");
            return;
        }
        Optional<Instant> exp = parseExpiry(header);
        if (exp.isEmpty()) {
            detail.add("토큰 만료일을 읽지 못했습니다");
            hints.add("만료일 헤더 형식을 해석하지 못했습니다 — GitHub 토큰 설정 화면에서 만료일을 직접 확인하세요.");
            sev.warn();
            return;
        }
        long days = Duration.between(now, exp.get()).toDays();
        String date = exp.get().toString().substring(0, 10);
        if (exp.get().isBefore(now)) {
            detail.add("토큰 만료일 " + date + " 이(가) 지났습니다");
            hints.add(REISSUE);
            sev.fail();
        } else if (days <= TOKEN_EXPIRY_WARN_DAYS) {
            detail.add("토큰 만료 임박: " + date + " (남은 " + days + "일)");
            hints.add("만료 전에 토큰을 재발급해 .env 의 GITHUB_TOKEN 을 교체하고 재시작하세요. " + TOKEN_HOWTO);
            sev.warn();
        } else {
            detail.add("토큰 만료일 " + date + " (남은 " + days + "일)");
        }
    }

    /** 응답 헤더 {@code 2026-12-31 03:46:04 UTC} 형식을 우선, ISO 형식도 받는다. 못 읽으면 empty. */
    static Optional<Instant> parseExpiry(String header) {
        String h = header.strip();
        try {
            return Optional.of(ZonedDateTime.parse(h, EXPIRY_FORMAT).toInstant());
        } catch (RuntimeException ignored) {
            // 다음 형식
        }
        try {
            return Optional.of(OffsetDateTime.parse(h).toInstant());
        } catch (RuntimeException ignored) {
            // 다음 형식
        }
        try {
            return Optional.of(Instant.parse(h));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** ok(0) &lt; warn(1) &lt; fail(2) 중 가장 나쁜 값을 기억한다. */
    private static final class Severity {
        private int level;

        void warn() {
            level = Math.max(level, 1);
        }

        void fail() {
            level = 2;
        }

        String name() {
            return level == 2 ? "fail" : level == 1 ? "warn" : "ok";
        }
    }
}
