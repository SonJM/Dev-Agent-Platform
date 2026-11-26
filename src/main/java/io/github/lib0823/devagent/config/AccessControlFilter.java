package io.github.lib0823.devagent.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * 상태를 바꾸는 요청(GET·HEAD·OPTIONS 외 전부, 경로 무관)의 신뢰 Origin 확인(FR-104).
 * Origin 이 있으면 루프백(이 서버 포트) 또는 access.allowed-origins 와 정확히 일치해야 하고, 아니면 403.
 * Origin 이 없으면(curl 등 로컬 도구) 통과. 컨트롤러마다 따로 확인하지 않는다.
 */
@Component
public class AccessControlFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AccessControlFilter.class);
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private static final String REJECT_BODY = "{\"ok\":false,\"result\":\"이 화면에서만 요청할 수 있습니다.\"}";

    private final List<String> allowedOrigins;
    private final int serverPort;

    /** serverPort 는 설정값을 쓴다 — 요청의 Host 헤더에서 읽으면 신뢰하는 루프백 Origin 이 요청에 따라 달라진다. */
    public AccessControlFilter(AccessProperties props, @Value("${server.port:8777}") int serverPort) {
        this.allowedOrigins = props.allowedOriginList();
        this.serverPort = serverPort;
    }

    /**
     * 경로로 거르지 않는다. 필터가 보는 원문 URI 와 Spring MVC 가 매칭하는 경로가 달라서
     * ({@code /api;x/…}, {@code /%61pi/…} 는 컨트롤러에 닿는다) 경로 조건은 우회된다. 안전한 메서드만 건너뛴다.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        return SAFE_METHODS.contains(req.getMethod().toUpperCase());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String origin = req.getHeader("Origin");
        if (origin == null || origin.isBlank() || isTrusted(origin)) {
            chain.doFilter(req, res);
            return;
        }
        // 헤더 값·본문은 남기지 않는다(메서드와 경로만).
        log.warn("신뢰하지 않는 Origin 의 상태 변경 요청을 거부했습니다: {} {}", req.getMethod(), req.getRequestURI());
        res.setStatus(HttpServletResponse.SC_FORBIDDEN);
        res.setContentType("application/json");
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        res.getWriter().write(REJECT_BODY);
    }

    private boolean isTrusted(String origin) {
        String self = ":" + serverPort;
        return origin.equals("http://127.0.0.1" + self)
                || origin.equals("http://localhost" + self)
                || allowedOrigins.contains(origin);
    }
}
