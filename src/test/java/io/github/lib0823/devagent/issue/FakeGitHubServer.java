package io.github.lib0823.devagent.issue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 시험용 가짜 GitHub API 서버(127.0.0.1, 임의 포트). 실제 GitHub 를 부르지 않는다.
 * 경로("METHOD /path")별로 응답을 등록하고, 받은 요청(메서드·경로+질의·Authorization·본문)을 기록한다.
 */
public final class FakeGitHubServer implements AutoCloseable {

    public record Reply(int status, String body, Map<String, String> headers, long delayMs) {
        public static Reply json(int status, String body) {
            return new Reply(status, body, Map.of(), 0);
        }

        public Reply header(String name, String value) {
            Map<String, String> h = new LinkedHashMap<>(headers);
            h.put(name, value);
            return new Reply(status, body, h, delayMs);
        }

        public Reply delayed(long ms) {
            return new Reply(status, body, headers, ms);
        }
    }

    public record Request(String method, String pathAndQuery, String authorization, String body) {
    }

    private final HttpServer server;
    private final Map<String, Reply> replies = new LinkedHashMap<>();
    private final List<Request> requests = Collections.synchronizedList(new ArrayList<>());

    public FakeGitHubServer() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", this::handle);
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** key 예: {@code "GET /repos/own/repo"} (질의 문자열 제외) 또는 {@code "GET /user/repos?page=2"} (질의 포함 키가 우선). */
    public FakeGitHubServer on(String key, Reply reply) {
        replies.put(key, reply);
        return this;
    }

    public List<Request> requests() {
        return List.copyOf(requests);
    }

    public Request last() {
        return requests.get(requests.size() - 1);
    }

    private void handle(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String pq = ex.getRequestURI().getRawPath() + (ex.getRequestURI().getRawQuery() == null ? "" : "?" + ex.getRequestURI().getRawQuery());
        requests.add(new Request(ex.getRequestMethod(), pq, ex.getRequestHeaders().getFirst("Authorization"), body));
        String base = ex.getRequestMethod() + " " + ex.getRequestURI().getRawPath();
        Reply r = replies.get(base + (ex.getRequestURI().getRawQuery() == null ? "" : "?" + ex.getRequestURI().getRawQuery()));
        if (r == null) {
            r = replies.get(base);                  // 질의 문자열을 포함한 키가 없으면 경로만으로
        }
        if (r == null) {
            r = Reply.json(404, "{\"message\":\"Not Found\"}");
        }
        if (r.delayMs() > 0) {
            try {
                Thread.sleep(r.delayMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] bytes = r.body().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        r.headers().forEach((k, v) -> ex.getResponseHeaders().add(k, v));
        try {
            ex.sendResponseHeaders(r.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                ex.getResponseBody().write(bytes);
            }
        } catch (IOException ignored) {
            // 클라이언트가 시간 초과로 먼저 끊은 경우
        } finally {
            ex.close();
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
