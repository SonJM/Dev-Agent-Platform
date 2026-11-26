package io.github.lib0823.devagent.gitlab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lib0823.devagent.config.GitHubProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * GitHub REST API 최소 클라이언트 (fine-grained PAT, Bearer 인증).
 * 저장소 조회 / 라벨 이슈 목록(PR 제외) / 이슈 단건 / 이슈 등록 / 이슈 코멘트 / Draft PR 생성.
 * 저장소는 {@code owner/repo} 로 식별한다. 모든 호출에 연결·응답 제한 시간이 있다.
 * 토큰은 로그·예외 메시지에 싣지 않는다.
 */
@Component
public class GitHubClient {

    private static final Logger log = LoggerFactory.getLogger(GitHubClient.class);

    static final int CONNECT_TIMEOUT_MS = 5_000;
    static final int READ_TIMEOUT_MS = 20_000;
    private static final int MAX_PER_PAGE = 100;
    private static final int MAX_MESSAGE_CHARS = 200;
    static final String API_VERSION = "2022-11-28";
    /** 응답 본문 상한(바이트). 이보다 크면 연결 실패로 취급한다(메모리 보호). */
    static final int MAX_RESPONSE_BYTES = 5 * 1024 * 1024;
    /** 이슈·코멘트 작성자로서 신뢰하는 author_association 값(저장소에 쓰기 관계가 있는 쪽). */
    private static final java.util.Set<String> TRUSTED_ASSOCIATIONS = java.util.Set.of("OWNER", "MEMBER", "COLLABORATOR");
    /** 토큰 만료일 응답 헤더(fine-grained PAT 일 때만 온다). */
    public static final String EXPIRATION_HEADER = "github-authentication-token-expiration";
    private static final Pattern REPO = Pattern.compile("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final GitHubProperties props;
    private final RestClient rest;

    @Autowired
    public GitHubClient(RestClient.Builder builder, GitHubProperties props) {
        this(builder, props, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
    }

    /** 시험용: 제한 시간을 짧게 줄 수 있다(생성자가 둘이라 공개 쪽에 @Autowired). */
    static GitHubClient withTimeouts(RestClient.Builder builder, GitHubProperties props, int connectMs, int readMs) {
        return new GitHubClient(builder, props, connectMs, readMs);
    }

    private GitHubClient(RestClient.Builder builder, GitHubProperties props, int connectMs, int readMs) {
        this.props = props;
        // JDK HttpClient 기반: HttpURLConnection 의 4xx POST·401 재시도 제약이 없다. 연결·응답 모두 제한 시간을 둔다.
        java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofMillis(connectMs)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(java.time.Duration.ofMillis(readMs));
        RestClient.Builder b = builder.baseUrl(props.apiBase()).requestFactory(factory)
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", API_VERSION)
                .defaultHeader(HttpHeaders.USER_AGENT, "dev-agent");
        if (props.hasToken() && props.apiUrlPolicy() != GitHubProperties.ApiUrlPolicy.INSECURE) {   // 평문 http 로는 토큰을 보내지 않는다
            b.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.token().strip());
        }
        this.rest = b.build();
    }

    /** 저장소 정보. id 는 표시용(호출에는 쓰지 않는다), path 는 owner/repo. */
    public record Project(long id, String path, String defaultBranch, String httpUrl, String webUrl) {
    }

    public record Issue(long iid, String title, String description, String webUrl,
                        List<String> labels, String author, String authorAssociation, String createdAt) {
        public String ref(String projectPath) {
            return projectPath + "#" + iid;
        }
    }

    /** Doctor 용 점검 결과(예외 없음). kind 가 null 이면 성공. tokenExpiration 은 응답 헤더 원문(없으면 null). */
    public record Probe(int status, GitHubException.Kind kind, String message, String tokenExpiration) {
        public boolean ok() {
            return kind == null;
        }
    }

    /** author_association 이 저장소 쓰기 관계(OWNER/MEMBER/COLLABORATOR)인지. */
    public static boolean isTrustedAssociation(String association) {
        return association != null && TRUSTED_ASSOCIATIONS.contains(association.strip().toUpperCase());
    }

    /** 신뢰 작성자 확인이 켜져 있는지(설정 github.trusted-authors-only, 기본 켜짐). */
    public boolean requiresTrustedAuthor() {
        return props.trustedAuthorsOnly();
    }

    // ---------------------------------------------------------------- 저장소

    public Project project(String repo) {
        String[] r = ownerRepo(repo);
        JsonNode p = send(HttpMethod.GET, "/repos/{o}/{r}", null, r[0], r[1]).body();
        if (p == null || !p.hasNonNull("id")) {
            throw new IllegalStateException("GitHub 저장소 조회 실패: " + repo);
        }
        String path = text(p, "full_name", repo);
        return new Project(p.get("id").asLong(), path, text(p, "default_branch", "main"),
                text(p, "clone_url", props.cloneUrl(path)), text(p, "html_url", props.repoUrl(path)));
    }

    /** 원격에 브랜치가 있는지. 404 → false(그 밖의 실패는 예외). */
    public boolean branchExists(String repo, String branch) {
        if (branch == null || branch.isBlank()) {
            return false;
        }
        String[] r = ownerRepo(repo);
        try {
            JsonNode b = send(HttpMethod.GET, "/repos/{o}/{r}/branches/{b}", null, r[0], r[1], branch).body();
            return b != null && b.hasNonNull("name");
        } catch (GitHubException e) {
            if (e.status() == 404) {
                return false;
            }
            throw e;
        }
    }

    /**
     * 브랜치 이름 목록(최대 100개, GitHub 가 주는 순서). search 가 있으면 이름에 포함된 것만. 질의 탭의 브랜치 선택용.
     * GitHub 는 브랜치 목록의 정렬·검색을 지원하지 않아 첫 페이지를 받아 거른다.
     */
    public List<String> branches(String repo, String search) {
        String[] r = ownerRepo(repo);
        JsonNode arr = send(HttpMethod.GET, "/repos/{o}/{r}/branches?per_page=" + MAX_PER_PAGE, null, r[0], r[1]).body();
        String needle = search == null ? "" : search.strip().toLowerCase();
        List<String> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            for (JsonNode n : arr) {
                String name = n.path("name").asText();
                if (!name.isEmpty() && (needle.isEmpty() || name.toLowerCase().contains(needle))) {
                    out.add(name);
                }
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- 이슈

    /** 라벨이 붙은 열린 이슈 (오래된 순, PR 제외, 최대 50건). label 이 비어 있으면 열린 이슈 전부. */
    public List<Issue> openIssues(String repo, String label) {
        String lbl = label == null ? "" : label.strip();
        String[] r = ownerRepo(repo);
        String tpl = "/repos/{o}/{r}/issues?state=open&sort=created&direction=asc&per_page=" + MAX_PER_PAGE
                + (lbl.isBlank() ? "" : "&labels={l}");
        JsonNode arr = (lbl.isBlank() ? send(HttpMethod.GET, tpl, null, r[0], r[1])
                : send(HttpMethod.GET, tpl, null, r[0], r[1], lbl)).body();
        return issuesOnly(arr, 50);
    }

    /** 열린 이슈 최근 갱신 순(라벨 무관, PR 제외, 최대 limit≤100건) — 에이전트 화면 "이슈 목록"용. */
    public List<Issue> recentOpenIssues(String repo, int limit) {
        String[] r = ownerRepo(repo);
        JsonNode arr = send(HttpMethod.GET, "/repos/{o}/{r}/issues?state=open&sort=updated&direction=desc&per_page=" + MAX_PER_PAGE,
                null, r[0], r[1]).body();
        return issuesOnly(arr, Math.max(1, Math.min(limit, MAX_PER_PAGE)));
    }

    public Issue issue(String repo, long number) {
        String[] r = ownerRepo(repo);
        JsonNode n = send(HttpMethod.GET, "/repos/{o}/{r}/issues/{n}", null, r[0], r[1], number).body();
        if (n == null || !n.hasNonNull("number")) {
            throw new IllegalStateException("GitHub 이슈 조회 실패: " + repo + "#" + number);
        }
        if (n.has("pull_request")) {
            throw new IllegalStateException(repo + "#" + number + " 는 이슈가 아니라 풀 리퀘스트입니다.");
        }
        return toIssue(n);
    }

    /** 이슈 등록(플랫폼 화면의 "새 이슈"). labels 는 쉼표 구분, 비어 있으면 라벨 없이. */
    public Issue createIssue(String repo, String title, String description, String labels) {
        String[] r = ownerRepo(repo);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        body.put("body", description == null ? "" : description);
        if (labels != null && !labels.isBlank()) {
            body.put("labels", List.of(labels.split(",")).stream().map(String::strip).filter(s -> !s.isEmpty()).toList());
        }
        JsonNode n = send(HttpMethod.POST, "/repos/{o}/{r}/issues", body, r[0], r[1]).body();
        if (n == null || !n.hasNonNull("number")) {
            throw new IllegalStateException("GitHub 이슈 등록 실패: " + repo);
        }
        log.info("GitHub 이슈 등록 repo={} number={}", repo, n.get("number").asLong());
        return toIssue(n);
    }

    /** 이슈 코멘트 본문 목록("작성자: 본문") — 분석 맥락으로 사용. 신뢰 작성자 확인이 켜져 있으면 OWNER/MEMBER/COLLABORATOR 의 것만. */
    public List<String> issueNotes(String repo, long number, int limit) {
        String[] r = ownerRepo(repo);
        int n = Math.max(1, Math.min(limit, MAX_PER_PAGE));
        JsonNode arr = send(HttpMethod.GET, "/repos/{o}/{r}/issues/{n}/comments?per_page=" + n, null, r[0], r[1], number).body();
        List<String> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            for (JsonNode c : arr) {
                if (props.trustedAuthorsOnly() && !isTrustedAssociation(text(c, "author_association", ""))) {
                    continue;
                }
                out.add(text(c.path("user"), "login", "?") + ": " + text(c, "body", ""));
            }
        }
        return out;
    }

    public void comment(String repo, long number, String body) {
        String[] r = ownerRepo(repo);
        send(HttpMethod.POST, "/repos/{o}/{r}/issues/{n}/comments", Map.of("body", body), r[0], r[1], number);
        log.info("GitHub 이슈 코멘트 작성 repo={} number={}", repo, number);
    }

    /** Draft PR 생성(head=작업 브랜치, base=대상 브랜치). 반환: PR 웹 주소. */
    public String createPullRequest(String repo, String head, String base, String title, String description) {
        String[] r = ownerRepo(repo);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        body.put("head", head);
        body.put("base", base);
        body.put("body", description);
        body.put("draft", true);
        JsonNode pr = send(HttpMethod.POST, "/repos/{o}/{r}/pulls", body, r[0], r[1]).body();
        String url = pr == null ? "" : text(pr, "html_url", "");
        log.info("GitHub PR 생성 repo={} {} -> {} url={}", repo, head, base, url);
        return url;
    }

    // ---------------------------------------------------------------- Doctor 용

    /** 저장소 접근 점검(owner/repo 형식 검증 후 URI 변수로 호출). 형식이 틀리면 호출 없이 실패 결과. */
    public Probe probeRepo(String repo) {
        String[] r;
        try {
            r = ownerRepo(repo);
        } catch (IllegalArgumentException e) {
            return new Probe(-1, GitHubException.Kind.REJECTED, "저장소 형식 오류(owner/repo)", null);
        }
        return probe("/repos/{o}/{r}", r[0], r[1]);
    }

    /** apiPath 템플릿(예: "/user", "/repos/{o}/{r}")을 GET 해 상태·만료 헤더를 돌려준다. 예외를 던지지 않는다. */
    public Probe probe(String apiPath, Object... vars) {
        try {
            Raw raw = exchange(HttpMethod.GET, apiPath, null, vars);
            String exp = raw.headers().getFirst(EXPIRATION_HEADER);
            if (raw.status() < 400) {
                return new Probe(raw.status(), null, "", exp);
            }
            GitHubException e = failure(raw);
            return new Probe(raw.status(), e.kind(), e.getMessage(), exp);
        } catch (GitHubException e) {
            return new Probe(e.status(), e.kind(), e.getMessage(), null);
        }
    }

    // ---------------------------------------------------------------- 내부

    private record Raw(int status, HttpHeaders headers, JsonNode body) {
    }

    private Raw send(HttpMethod method, String template, Object body, Object... vars) {
        Raw raw = exchange(method, template, body, vars);
        if (raw.status() >= 400) {
            throw failure(raw);
        }
        return raw;
    }

    private Raw exchange(HttpMethod method, String template, Object body, Object... vars) {
        if (props.apiUrlPolicy() == GitHubProperties.ApiUrlPolicy.INSECURE) {
            throw new GitHubException(GitHubException.Kind.NETWORK, -1, "GitHub API 주소는 https 여야 합니다(GITHUB_API_URL)");
        }
        try {
            RestClient.RequestBodySpec spec = rest.method(method).uri(template, vars);
            if (body != null) {
                spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
            }
            return spec.exchange((req, res) -> {
                byte[] bytes = res.getBody().readNBytes(MAX_RESPONSE_BYTES + 1);
                if (bytes.length > MAX_RESPONSE_BYTES) {
                    throw new GitHubException(GitHubException.Kind.NETWORK, -1, "GitHub 응답이 너무 큽니다");
                }
                JsonNode json = null;
                if (bytes.length > 0) {
                    try {
                        json = JSON.readTree(bytes);
                    } catch (IOException e) {
                        json = null;                       // JSON 이 아닌 본문(프록시 오류 페이지 등)은 문구 없이 상태 코드만
                    }
                }
                return new Raw(res.getStatusCode().value(), res.getHeaders(), json);
            });
        } catch (RestClientException e) {
            throw new GitHubException(GitHubException.Kind.NETWORK, -1, "GitHub 연결 실패(" + e.getClass().getSimpleName() + ")");
        }
    }

    private GitHubException failure(Raw raw) {
        String msg = raw.body() == null ? "" : text(raw.body(), "message", "");
        String remaining = raw.headers().getFirst("x-ratelimit-remaining");
        boolean remainingZero = "0".equals(remaining == null ? "" : remaining.strip());
        boolean retryAfter = raw.headers().getFirst(HttpHeaders.RETRY_AFTER) != null;
        GitHubException.Kind kind = GitHubException.classify(raw.status(), remainingZero, retryAfter, msg);
        String label = switch (kind) {
            case UNAUTHORIZED -> "토큰 무효·만료";
            case NOT_ACCESSIBLE -> "접근 불가(저장소 없음 또는 토큰 범위 밖)";
            case RATE_LIMITED -> "응답 한도 초과";
            case REJECTED -> "요청 거부";
            default -> "오류";
        };
        String detail = sanitize(msg);
        return new GitHubException(kind, raw.status(), "GitHub " + raw.status() + " " + label + (detail.isEmpty() ? "" : ": " + detail));
    }

    /** GitHub 오류 문구에서 토큰 값을 가리고 길이를 줄인다. */
    private String sanitize(String s) {
        String t = s == null ? "" : s.strip().replaceAll("\\s+", " ");
        if (props.hasToken()) {
            t = t.replace(props.token().strip(), "***");
        }
        return t.length() > MAX_MESSAGE_CHARS ? t.substring(0, MAX_MESSAGE_CHARS) + "…" : t;
    }

    /** "owner/repo" 형식만 허용(경로 조작 방지). */
    static String[] ownerRepo(String repo) {
        if (repo == null || !REPO.matcher(repo.strip()).matches()) {
            throw new IllegalArgumentException("저장소는 owner/repo 형식이어야 합니다: " + repo);
        }
        String[] parts = repo.strip().split("/");
        for (String part : parts) {
            if (part.equals(".") || part.equals("..")) {
                throw new IllegalArgumentException("저장소 이름에 '.'·'..' 은 쓸 수 없습니다: " + repo);
            }
        }
        return parts;
    }

    private static List<Issue> issuesOnly(JsonNode arr, int limit) {
        List<Issue> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            for (JsonNode n : arr) {
                if (n.has("pull_request")) {
                    continue;                              // 이슈 목록 API 는 PR 도 섞어 준다
                }
                out.add(toIssue(n));
                if (out.size() >= limit) {
                    break;
                }
            }
        }
        return out;
    }

    private static Issue toIssue(JsonNode n) {
        List<String> labels = new ArrayList<>();
        if (n.path("labels").isArray()) {
            n.path("labels").forEach(l -> labels.add(l.isTextual() ? l.asText() : l.path("name").asText()));
        }
        return new Issue(n.path("number").asLong(), text(n, "title", ""), text(n, "body", ""),
                text(n, "html_url", ""), labels, text(n.path("user"), "login", "?"), text(n, "author_association", ""), text(n, "created_at", ""));
    }

    private static String text(JsonNode n, String field, String dflt) {
        return n != null && n.hasNonNull(field) ? n.get(field).asText() : dflt;
    }
}
