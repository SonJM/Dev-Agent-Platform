package io.github.lib0823.devagent.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** TC-002, TC-003 — 상태 변경 /api 요청의 신뢰 Origin 확인. */
class AccessControlFilterTest {

    private static final int PORT = 8777;
    private static final String SERVE = "https://host.example.ts.net:8443";

    private static AccessControlFilter filter(AccessProperties props) {
        return new AccessControlFilter(props, PORT);
    }

    private static MockHttpServletRequest req(String method, String uri, String origin) {
        MockHttpServletRequest r = new MockHttpServletRequest(method, uri);
        r.setServerPort(PORT);
        if (origin != null) {
            r.addHeader("Origin", origin);
        }
        return r;
    }

    private static MockFilterChain run(AccessControlFilter f, MockHttpServletRequest rq, MockHttpServletResponse rs) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        f.doFilter(rq, rs, chain);
        return chain;
    }

    private static boolean passed(MockFilterChain chain) {
        return chain.getRequest() != null;
    }

    @Test
    void TC002_untrustedOriginPostIsRejectedWith403() throws Exception {
        AccessControlFilter f = filter(new AccessProperties(""));
        MockHttpServletResponse rs = new MockHttpServletResponse();
        MockFilterChain chain = run(f, req("POST", "/api/skills", "https://evil.example"), rs);

        assertEquals(403, rs.getStatus());
        assertEquals(false, passed(chain));
        assertTrue(rs.getContentAsString().contains("\"ok\":false"));
        assertTrue(rs.getContentType().startsWith("application/json"));
    }

    @Test
    void TC002_allMutatingMethodsAreChecked() throws Exception {
        AccessControlFilter f = filter(new AccessProperties(""));
        for (String m : new String[]{"POST", "PUT", "DELETE", "PATCH"}) {
            MockHttpServletResponse rs = new MockHttpServletResponse();
            MockFilterChain chain = run(f, req(m, "/api/anything", "http://attacker.example:8777"), rs);
            assertEquals(403, rs.getStatus(), m);
            assertEquals(false, passed(chain), m);
        }
    }

    @Test
    void TC002_nullOriginStringAndLookalikeAreRejected() throws Exception {
        AccessControlFilter f = filter(new AccessProperties(SERVE));
        for (String o : new String[]{"null", "http://127.0.0.1:9999", "http://localhost", SERVE + "/", "https://host.example.ts.net:8443.evil.com"}) {
            MockHttpServletResponse rs = new MockHttpServletResponse();
            MockFilterChain chain = run(f, req("POST", "/api/skills", o), rs);
            assertEquals(403, rs.getStatus(), o);
            assertEquals(false, passed(chain), o);
        }
    }

    @Test
    void TC003_configuredOriginLoopbackNoOriginAndGetAllPass() throws Exception {
        AccessControlFilter f = filter(new AccessProperties(SERVE));

        MockFilterChain c1 = run(f, req("POST", "/api/skills", SERVE), new MockHttpServletResponse());
        MockFilterChain c2 = run(f, req("POST", "/api/skills", "http://127.0.0.1:" + PORT), new MockHttpServletResponse());
        MockFilterChain c3 = run(f, req("POST", "/api/skills", "http://localhost:" + PORT), new MockHttpServletResponse());
        MockFilterChain c4 = run(f, req("POST", "/api/skills", null), new MockHttpServletResponse());
        MockFilterChain c5 = run(f, req("GET", "/api/skills", "https://evil.example"), new MockHttpServletResponse());

        assertTrue(passed(c1), "configured origin");
        assertTrue(passed(c2), "loopback ip");
        assertTrue(passed(c3), "localhost");
        assertTrue(passed(c4), "no origin");
        assertTrue(passed(c5), "GET with other origin");
    }

    @Test
    void TC003_headAndOptionsPass() throws Exception {
        AccessControlFilter f = filter(new AccessProperties(""));
        assertTrue(passed(run(f, req("HEAD", "/api/x", "https://evil.example"), new MockHttpServletResponse())));
        assertTrue(passed(run(f, req("OPTIONS", "/api/x", "https://evil.example"), new MockHttpServletResponse())));
    }

    /** 경로 표기를 바꿔도(matrix 파라미터·퍼센트 인코딩·/api 밖) 검사를 건너뛰지 않는다 — Spring MVC 는 이런 경로도 컨트롤러에 매칭한다. */
    @Test
    void TC002_pathSpellingCannotBypassTheCheck() throws Exception {
        AccessControlFilter f = filter(new AccessProperties(""));
        for (String uri : new String[]{"/api;x/maintain", "/%61pi/maintain", "/api/maintain;x", "/API/maintain", "//api/maintain", "/home.html", "/"}) {
            MockHttpServletResponse rs = new MockHttpServletResponse();
            MockFilterChain chain = run(f, req("POST", uri, "https://evil.example"), rs);
            assertEquals(403, rs.getStatus(), uri);
            assertEquals(false, passed(chain), uri);
        }
    }

    /** 신뢰하는 루프백 포트는 설정값이다 — 요청의 Host 포트를 따라가지 않는다. */
    @Test
    void TC002_loopbackPortComesFromConfigNotFromHostHeader() throws Exception {
        AccessControlFilter f = filter(new AccessProperties(""));
        MockHttpServletRequest rq = req("POST", "/api/skills", "http://localhost:9999");
        rq.setServerPort(9999);
        MockHttpServletResponse rs = new MockHttpServletResponse();
        MockFilterChain chain = run(f, rq, rs);
        assertEquals(403, rs.getStatus());
        assertEquals(false, passed(chain));
    }

    @Test
    void TC003_commaSeparatedListWithSpacesAndBlanksIsParsed() throws Exception {
        AccessControlFilter f = filter(new AccessProperties(" https://a.example , ,https://b.example:8443 "));
        assertTrue(passed(run(f, req("POST", "/api/x", "https://a.example"), new MockHttpServletResponse())));
        assertTrue(passed(run(f, req("POST", "/api/x", "https://b.example:8443"), new MockHttpServletResponse())));
        MockHttpServletResponse rs = new MockHttpServletResponse();
        run(f, req("POST", "/api/x", "https://c.example"), rs);
        assertEquals(403, rs.getStatus());
    }

    @Test
    void TC003_emptyOrNullSettingKeepsLoopbackOnlyBehavior() throws Exception {
        for (AccessProperties p : new AccessProperties[]{new AccessProperties(""), new AccessProperties(null)}) {
            AccessControlFilter f = filter(p);
            assertTrue(passed(run(f, req("POST", "/api/x", "http://localhost:" + PORT), new MockHttpServletResponse())));
            MockHttpServletResponse rs = new MockHttpServletResponse();
            run(f, req("POST", "/api/x", SERVE), rs);
            assertEquals(403, rs.getStatus());
        }
    }

    @Test
    void rejectionBodyDoesNotEchoOrigin() throws Exception {
        AccessControlFilter f = filter(new AccessProperties(""));
        MockHttpServletResponse rs = new MockHttpServletResponse();
        run(f, req("POST", "/api/x", "https://evil.example"), rs);
        assertTrue(!rs.getContentAsString().contains("evil"));
        assertNull(rs.getErrorMessage());
    }
}
