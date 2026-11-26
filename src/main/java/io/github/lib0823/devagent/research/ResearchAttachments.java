package io.github.lib0823.devagent.research;

import io.github.lib0823.devagent.config.IssueProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 리서치 질문에 붙이는 이미지(화면 캡처·다이어그램). 에이전트 작업 디렉터리 아래 {@code research-attach/} 에 파일로 둔다:
 * 화면이 올리면 먼저 {@code tmp/<uuid>.<확장자>} 에 받아 두고(접수 전), 질문이 접수되면 {@code <질의 id>/<순번>.<확장자>} 로 옮긴다.
 * 모델에는 파일의 절대 경로를 알려 Read 도구로 직접 열게 한다(코딩 에이전트 포트에 이미지 입력을 따로 두지 않는다).
 * 내용의 앞머리(매직 바이트)로 형식을 확인하고 이름은 서버가 정한다 — 올린 쪽이 준 이름·확장자는 쓰지 않는다.
 */
@Component
public class ResearchAttachments {

    private static final Logger log = LoggerFactory.getLogger(ResearchAttachments.class);
    public static final int MAX_FILES = 6;
    public static final int MAX_BYTES = 10 * 1024 * 1024;
    private static final long TMP_KEEP_MS = 24 * 60 * 60 * 1000L;
    private static final Pattern STAGED = Pattern.compile("[0-9a-f-]{36}\\.(png|jpg|gif|webp)");
    private static final Pattern STORED = Pattern.compile("\\d{1,2}\\.(png|jpg|gif|webp)");

    private final Path root;

    @org.springframework.beans.factory.annotation.Autowired   // 생성자가 둘(아래는 테스트용)이라 Spring 이 쓸 것을 표시해야 한다 — 없으면 기동 실패
    public ResearchAttachments(IssueProperties issueProps) {
        this(Path.of(issueProps.workDir() == null || issueProps.workDir().isBlank() ? "work" : issueProps.workDir()).toAbsolutePath().resolve("research-attach"));
    }

    ResearchAttachments(Path root) {
        this.root = root;
    }

    /** 이미지 형식 판별(png / jpg / gif / webp). 이미지가 아니면 null. */
    static String imageType(byte[] d) {
        if (d == null || d.length < 12) {
            return null;
        }
        if ((d[0] & 0xff) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') {
            return "png";
        }
        if ((d[0] & 0xff) == 0xff && (d[1] & 0xff) == 0xd8 && (d[2] & 0xff) == 0xff) {
            return "jpg";
        }
        if (d[0] == 'G' && d[1] == 'I' && d[2] == 'F' && d[3] == '8') {
            return "gif";
        }
        if (d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F' && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P') {
            return "webp";
        }
        return null;
    }

    /** 올린 이미지를 임시 보관하고 그 이름을 돌려준다(접수 때 {@link #claim} 에 넘길 값). 형식·크기가 맞지 않으면 IllegalArgumentException. */
    public String stage(byte[] data) {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("빈 파일입니다.");
        }
        if (data.length > MAX_BYTES) {
            throw new IllegalArgumentException("이미지가 너무 큽니다(" + MAX_BYTES / (1024 * 1024) + "MB 이내).");
        }
        String type = imageType(data);
        if (type == null) {
            throw new IllegalArgumentException("이미지 파일만 첨부할 수 있습니다(PNG · JPG · GIF · WebP).");
        }
        try {
            Path tmp = root.resolve("tmp");
            Files.createDirectories(tmp);
            sweep(tmp);
            String name = UUID.randomUUID() + "." + type;
            Files.write(tmp.resolve(name), data);
            return name;
        } catch (IOException e) {
            throw new IllegalStateException("첨부 저장 실패: " + e.getMessage(), e);
        }
    }

    /** 접수되지 않고 남은 임시 파일(하루 지난 것)을 지운다. */
    private void sweep(Path tmp) {
        long old = System.currentTimeMillis() - TMP_KEEP_MS;
        try (Stream<Path> s = Files.list(tmp)) {
            s.filter(p -> {
                try {
                    return Files.getLastModifiedTime(p).toMillis() < old;
                } catch (IOException e) {
                    return false;
                }
            }).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignore) {
                    // 다음 기회에
                }
            });
        } catch (IOException e) {
            log.debug("첨부 임시 폴더 정리 실패: {}", e.toString());
        }
    }

    /** 요청에 실린 임시 이름 목록을 검증한다(형식·개수·존재). 문제가 있으면 IllegalArgumentException. */
    public List<String> check(List<String> staged) {
        List<String> out = new ArrayList<>();
        if (staged == null) {
            return out;
        }
        for (String s : staged) {
            String n = s == null ? "" : s.strip();
            if (n.isEmpty() || out.contains(n)) {
                continue;
            }
            if (!STAGED.matcher(n).matches() || !Files.isRegularFile(root.resolve("tmp").resolve(n))) {
                throw new IllegalArgumentException("첨부 이미지를 찾을 수 없습니다. 다시 첨부해 주세요.");
            }
            out.add(n);
        }
        if (out.size() > MAX_FILES) {
            throw new IllegalArgumentException("이미지는 질문 하나에 " + MAX_FILES + "개까지 첨부할 수 있습니다.");
        }
        return out;
    }

    /** 임시 파일들을 질의 폴더로 옮기고 저장된 이름(1.png, 2.jpg …)을 돌려준다. */
    public List<String> claim(long jobId, List<String> staged) {
        List<String> names = new ArrayList<>();
        if (staged == null || staged.isEmpty()) {
            return names;
        }
        try {
            Path dir = root.resolve(String.valueOf(jobId));
            Files.createDirectories(dir);
            int n = 0;
            for (String s : staged) {
                String name = (++n) + s.substring(s.lastIndexOf('.'));
                Files.move(root.resolve("tmp").resolve(s), dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                names.add(name);
            }
            return names;
        } catch (IOException e) {
            throw new IllegalStateException("첨부 저장 실패: " + e.getMessage(), e);
        }
    }

    /** 첨부 폴더가 남아 있는 질의 id 들(정리 대상 후보). */
    public List<Long> jobIds() {
        List<Long> ids = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return ids;
        }
        try (Stream<Path> s = Files.list(root)) {
            s.filter(Files::isDirectory).map(p -> p.getFileName().toString()).filter(n -> n.matches("\\d{1,18}"))
                    .forEach(n -> ids.add(Long.parseLong(n)));
        } catch (IOException e) {
            log.debug("첨부 폴더 목록 조회 실패: {}", e.toString());
        }
        return ids;
    }

    /** 폴더가 마지막으로 바뀐 시각(ms). 질의 기록이 없는 폴더의 나이를 볼 때 쓴다. 못 읽으면 지금. */
    public long folderTime(long jobId) {
        try {
            return Files.getLastModifiedTime(root.resolve(String.valueOf(jobId))).toMillis();
        } catch (IOException e) {
            return System.currentTimeMillis();
        }
    }

    /** 질의 하나의 첨부 폴더를 지운다(파일 → 폴더 순). 다 지웠으면 true. */
    public boolean delete(long jobId) {
        Path dir = root.resolve(String.valueOf(jobId));
        if (!Files.isDirectory(dir)) {
            return true;
        }
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.toList()) {
                Files.deleteIfExists(p);
            }
            Files.deleteIfExists(dir);
            return true;
        } catch (IOException e) {
            log.warn("첨부 폴더 삭제 실패 #{}: {}", jobId, e.toString());
            return false;
        }
    }

    /** 저장된 첨부 파일. 이름이 규칙에 맞지 않거나 없으면 null. */
    public Path file(long jobId, String name) {
        if (name == null || !STORED.matcher(name).matches()) {
            return null;
        }
        Path p = root.resolve(String.valueOf(jobId)).resolve(name);
        return Files.isRegularFile(p) ? p : null;
    }

    /**
     * 프롬프트에 덧붙일 첨부 안내(없으면 빈 문자열). 모델이 Read 도구로 열 수 있게 절대 경로를 준다.
     * 지워진 파일은 건너뛴다.
     */
    public String promptNote(ResearchJob j) {
        StringBuilder sb = new StringBuilder();
        for (String name : j.attachmentList()) {
            Path p = file(j.getId(), name);
            if (p != null) {
                sb.append("\n- ").append(p.toAbsolutePath().toString().replace('\\', '/'));
            }
        }
        if (sb.length() == 0) {
            return "";
        }
        return "\n\n--- 첨부 이미지 ---\n질문에 이미지가 첨부되어 있습니다. 아래 파일을 Read 도구로 먼저 열어 내용(화면·문구·표·오류 메시지)을 확인한 뒤 답하세요. "
                + "이미지는 저장소 밖에 있는 참고 자료이며 수정 대상이 아닙니다." + sb;
    }
}
