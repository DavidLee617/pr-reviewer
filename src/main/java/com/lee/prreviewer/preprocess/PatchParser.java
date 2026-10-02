package com.lee.prreviewer.preprocess;

import com.lee.prreviewer.model.ChangeType;
import com.lee.prreviewer.model.DiffLine;
import com.lee.prreviewer.model.FileDiff;
import com.lee.prreviewer.model.Hunk;
import com.lee.prreviewer.model.LineType;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 把 GitHub 返回的单文件 patch（以 @@ 开头、无文件头）解析成带新文件行号的 FileDiff。
 * <p>
 * annotatedDiff 格式（设计文档 6.2），每行左侧是新文件行号，删除行用 "-" 占位：
 * <pre>
 * File: src/main/java/com/example/demo/service/BookService.java
 *   41 |   public Book getById(Long id) {
 *   42 | +     log.info("get book {}", id);
 *    - | -     return bookRepository.getOne(id);
 *   43 | +     return bookRepository.findById(id).orElseThrow();
 * </pre>
 * 在 Windows 上提交的文件可能是 CRLF 换行，行尾的 \r 会被去掉。
 */
@Component
public class PatchParser {

    /** @@ -oldStart[,oldCount] +newStart[,newCount] @@ 可选的段落标题（如所在方法名） */
    private static final Pattern HUNK_HEADER = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,(\\d+))? @@.*");

    public FileDiff parse(String path, ChangeType changeType, String patch) {
        List<Hunk> hunks = parseHunks(patch);
        return new FileDiff(path, changeType, hunks, annotate(path, hunks));
    }

    List<Hunk> parseHunks(String patch) {
        List<Hunk> hunks = new ArrayList<>();
        if (patch == null || patch.isEmpty()) {
            return hunks;
        }
        int newStart = 0;
        int newCount = 0;
        int nextNewLine = 0;
        List<DiffLine> lines = null;

        for (String raw : patch.split("\n", -1)) { // -1：保留末尾空串（≈ StringSplitOptions.None）
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            Matcher header = HUNK_HEADER.matcher(line);
            if (header.matches()) {
                if (lines != null) {
                    hunks.add(new Hunk(newStart, newCount, lines));
                }
                newStart = Integer.parseInt(header.group(1));
                newCount = header.group(2) == null ? 1 : Integer.parseInt(header.group(2)); // 省略 count 表示 1
                nextNewLine = newStart;
                lines = new ArrayList<>();
                continue;
            }
            if (lines == null || line.startsWith("\\")) {
                continue; // hunk 之前的内容，或 "\ No newline at end of file"
            }
            if (line.isEmpty()) {
                // patch 末尾的空串；hunk 中间的空上下文行至少会有一个前导空格
                continue;
            }
            char marker = line.charAt(0);
            String content = line.substring(1);
            if (content.startsWith("\uFEFF")) {
                content = content.substring(1); // Windows 编辑器保存的文件可能带 UTF-8 BOM，出现在第一行开头
            }
            switch (marker) {
                case '+' -> lines.add(new DiffLine(LineType.ADDED, nextNewLine++, content));
                case '-' -> lines.add(new DiffLine(LineType.REMOVED, null, content));
                default -> lines.add(new DiffLine(LineType.CONTEXT, nextNewLine++, content));
            }
        }
        if (lines != null) {
            hunks.add(new Hunk(newStart, newCount, lines));
        }
        return hunks;
    }

    static String annotate(String path, List<Hunk> hunks) {
        int maxLine = hunks.stream()
                .flatMap(h -> h.lines().stream())             // ≈ LINQ SelectMany
                .map(DiffLine::newLineNo)
                .filter(n -> n != null)
                .max(Integer::compare)
                .orElse(0);
        int width = Math.max(4, String.valueOf(maxLine).length());
        String lineFormat = "%" + width + "s | %c %s\n"; // 用 \n 而不是 %n，避免 Windows 上输出 \r\n

        StringBuilder sb = new StringBuilder("File: ").append(path).append('\n');
        for (int i = 0; i < hunks.size(); i++) {
            if (i > 0) {
                sb.append(" ".repeat(width - 3)).append("... |\n"); // hunk 之间有未展示的代码
            }
            for (DiffLine l : hunks.get(i).lines()) {
                String no = l.newLineNo() == null ? "-" : l.newLineNo().toString();
                char marker = switch (l.type()) {
                    case ADDED -> '+';
                    case REMOVED -> '-';
                    case CONTEXT -> ' ';
                };
                sb.append(String.format(lineFormat, no, marker, l.content()));
            }
        }
        return sb.toString();
    }
}
