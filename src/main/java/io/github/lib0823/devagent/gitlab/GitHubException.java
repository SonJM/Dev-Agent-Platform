package io.github.lib0823.devagent.gitlab;

/**
 * GitHub REST 호출 실패. 원인을 {@link Kind} 로 구분하고, 메시지에는 상태 코드와 GitHub 오류 문구만 싣는다(토큰·헤더 없음).
 */
public class GitHubException extends RuntimeException {

    public enum Kind {
        /** 401: 토큰 무효·만료. */
        UNAUTHORIZED,
        /** 403/404: 저장소가 토큰의 선택 범위 밖이거나 없음(조직 저장소 포함). */
        NOT_ACCESSIBLE,
        /** 응답 한도(rate limit). */
        RATE_LIMITED,
        /** 422 등 요청 자체가 거부됨(이미 있는 PR 등). */
        REJECTED,
        /** 연결 실패·시간 초과. */
        NETWORK,
        OTHER
    }

    private final Kind kind;
    private final int status;

    public GitHubException(Kind kind, int status, String message) {
        super(message);
        this.kind = kind;
        this.status = status;
    }

    public Kind kind() {
        return kind;
    }

    public int status() {
        return status;
    }

    /** 상태 코드와 한도 헤더로 실패 종류를 가른다. 403 은 한도 소진(remaining=0, retry-after, 문구)이면 RATE_LIMITED. */
    static Kind classify(int status, boolean remainingZero, boolean retryAfter, String message) {
        String m = message == null ? "" : message.toLowerCase();
        boolean limitText = m.contains("rate limit") || m.contains("abuse detection") || m.contains("secondary rate");
        if (status == 429 || ((status == 403) && (remainingZero || retryAfter || limitText))) {
            return Kind.RATE_LIMITED;
        }
        return switch (status) {
            case 401 -> Kind.UNAUTHORIZED;
            case 403, 404 -> Kind.NOT_ACCESSIBLE;
            case 400, 409, 410, 422 -> Kind.REJECTED;
            default -> Kind.OTHER;
        };
    }
}
