package io.github.lib0823.devagent.agent.claude;

import io.github.lib0823.devagent.config.PreviewProperties;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 에이전트용 {@code ~/bin} 실행 심(preview / jscheck / jspcheck / mvn) 작성. 배경은 {@link ClaudeCliAgent#installShims()}.
 * <ul>
 *   <li>Windows: 지금까지와 같다(항상 쓴다, 내용이 같으면 건너뜀).</li>
 *   <li>그 외(서버): 프리뷰가 꺼져 있으면 아무것도 쓰지 않고 {@code ~/bin} 도 만들지 않는다. 프리뷰가 켜져 있어도 사용자가 둔
 *       같은 이름의 파일(우리 표식이 없는 것)은 덮어쓰지 않는다 — 서버의 홈은 사용자의 실제 계정이다.</li>
 * </ul>
 */
final class AgentShims {

    static final String MARKER = "dev-agent 이 생성한 심";

    private AgentShims() {
    }

    /** 실제로 쓴(바뀐) 심 이름 목록을 돌려준다. */
    static List<String> install(Path bin, Path scripts, int adminPort, PreviewProperties preview, boolean windows) throws IOException {
        boolean previewOn = preview != null && preview.enabled();
        List<String> written = new ArrayList<>();
        if (!windows && !previewOn) {
            return written;
        }
        Files.createDirectories(bin);
        String envLines = "export DEV_AGENT_PORT=\"" + adminPort + "\"\n"
                + (previewOn
                   ? "export PREVIEW_JAVA_HOME=\"" + fwd(preview.javaHome()) + "\"\n"
                   + "export PREVIEW_CATALINA_HOME=\"" + fwd(preview.catalinaHome()) + "\"\n"
                   + "export PREVIEW_URL=\"" + preview.url() + "\"\n" : "");
        for (String name : List.of("preview", "jscheck", "jspcheck")) {
            String body = "#!/usr/bin/env bash\n# " + MARKER + " — 수정하지 말 것(기동 때 다시 씀)\n" + envLines
                    + "exec \"" + fwd(scripts.resolve(name).toString()) + "\" \"$@\"\n";
            if (write(bin.resolve(name), body, windows)) {
                written.add(name);
            }
        }
        Path mvnCmd = previewOn && preview.mavenHome() != null && !preview.mavenHome().isBlank()
                ? Path.of(preview.mavenHome()).resolve("bin").resolve("mvn.cmd") : null;
        if (mvnCmd != null && Files.isRegularFile(mvnCmd)) {
            String body = "#!/usr/bin/env bash\n# " + MARKER + " — 프리뷰용 Maven(JDK 21)\n"
                    + "export JAVA_HOME=\"" + fwd(preview.javaHome()) + "\"\n"
                    + "export PATH=\"" + fwd(preview.javaHome()) + "/bin:$PATH\"\n"
                    + "exec \"" + fwd(mvnCmd.toString()) + "\" \"$@\"\n";
            if (write(bin.resolve("mvn"), body, windows)) {
                written.add("mvn");
            }
        }
        return written;
    }

    private static boolean write(Path file, String content, boolean windows) throws IOException {
        if (!windows && Files.isSymbolicLink(file)) {
            return false;            // 링크는 사용자의 것 — 따라가서 ~/bin 밖에 쓰지 않는다(끊어진 링크 포함)
        }
        if (Files.isRegularFile(file)) {
            String current = readOrNull(file);
            if (current == null) {
                if (!windows) {
                    return false;    // 읽지 못하는 파일(바이너리 등)은 사용자의 것으로 본다
                }
            } else if (content.equals(current) || (!windows && !current.contains(MARKER))) {
                return false;        // 같은 내용이거나(서버에서는) 사용자의 파일 — 건드리지 않는다
            }
        }
        Files.writeString(file, content, StandardCharsets.UTF_8);   // LF 유지(bash 스크립트)
        file.toFile().setExecutable(true);                          // macOS/Linux: 실행 권한(Windows 는 무시됨)
        return true;
    }

    /** UTF-8 텍스트가 아니면 null. */
    private static String readOrNull(Path file) throws IOException {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    /** Windows 경로를 bash 가 그대로 받는 "C:/..." 슬래시 형태로. */
    private static String fwd(String p) {
        return p == null ? "" : p.replace('\\', '/');
    }
}
