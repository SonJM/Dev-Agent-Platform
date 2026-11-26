package io.github.lib0823.devagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.net.URI;

/**
 * GitHub 연동 설정 (prefix: github). 토큰은 fine-grained PAT(개인 계정의 대상 저장소만)이고 서버 .env 의 GITHUB_TOKEN 에만 둔다.
 * 토큰이 비어도 기동된다(Doctor 가 원인을 알려 준다).
 */
@ConfigurationProperties(prefix = "github")
public record GitHubProperties(
        String token,                  // fine-grained PAT (Contents·Issues·Pull requests 읽기/쓰기 + Metadata 읽기)
        String apiUrl,                 // REST API 주소 (기본: 공개 GitHub API)
        boolean trustedAuthorsOnly     // 이슈 본문·코멘트 작성자가 OWNER/MEMBER/COLLABORATOR 일 때만 처리·프롬프트 반영(기본 켜짐)
) {

    public static final String DEFAULT_API_URL = "https://api.github.com";

    /** api-url 정책: https 만 허용. 시험용 가짜 서버를 위해 localhost·127.0.0.1 의 http 는 허용하되 Doctor 가 경고한다. */
    public enum ApiUrlPolicy { OK, LOCAL_HTTP, INSECURE }

    /** 신뢰 작성자 확인이 기본 켜짐인 생성자(시험·코드에서 설정 없이 만들 때). */
    public GitHubProperties(String token, String apiUrl) {
        this(token, apiUrl, true);
    }

    @ConstructorBinding
    public GitHubProperties {
    }

    public ApiUrlPolicy apiUrlPolicy() {
        URI u;
        try {
            u = URI.create(apiBase());
        } catch (IllegalArgumentException e) {
            return ApiUrlPolicy.INSECURE;
        }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase();
        if ("https".equals(scheme) && !host.isEmpty()) {
            return ApiUrlPolicy.OK;
        }
        if ("http".equals(scheme) && ("localhost".equals(host) || "127.0.0.1".equals(host))) {
            return ApiUrlPolicy.LOCAL_HTTP;
        }
        return ApiUrlPolicy.INSECURE;
    }

    public boolean hasToken() {
        return token != null && !token.isBlank();
    }

    /** REST API 기준 주소(끝 '/' 제거, 비어 있으면 공개 API 주소). */
    public String apiBase() {
        return apiUrl == null || apiUrl.isBlank() ? DEFAULT_API_URL : apiUrl.strip().replaceAll("/+$", "");
    }

    /**
     * 웹·git 호스트 기준 주소(끝 '/' 없음). 공개 API 주소면 https://github.com,
     * 그 외(GitHub Enterprise 의 {@code https://host/api/v3} 등)면 API 주소에서 {@code /api/v3} 또는 {@code api.} 접두를 뗀다.
     */
    public String webBase() {
        String base = apiBase();
        URI u = URI.create(base);
        String host = u.getHost() == null ? "" : u.getHost();
        String scheme = u.getScheme() == null ? "https" : u.getScheme();
        int port = u.getPort();
        String authority = host + (port > 0 ? ":" + port : "");
        if ("api.github.com".equalsIgnoreCase(host)) {
            return "https://github.com";
        }
        if (base.endsWith("/api/v3")) {
            return base.substring(0, base.length() - "/api/v3".length());
        }
        if (host.startsWith("api.")) {
            return scheme + "://" + (host.substring(4) + (port > 0 ? ":" + port : ""));
        }
        return scheme + "://" + authority;
    }

    /** "owner/repo" → git clone 주소. */
    public String cloneUrl(String repo) {
        return webBase() + "/" + repo + ".git";
    }

    /** "owner/repo" → 저장소 웹 주소. */
    public String repoUrl(String repo) {
        return webBase() + "/" + repo;
    }
}
