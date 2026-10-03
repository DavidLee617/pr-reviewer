package com.lee.prreviewer.app;

import com.lee.prreviewer.model.Finding;
import com.lee.prreviewer.model.ReviewError;
import com.lee.prreviewer.model.ReviewReport;
import com.lee.prreviewer.model.Severity;
import com.lee.prreviewer.model.SkippedFile;
import java.util.List;

/** 把 ReviewReport 渲染成给人看的 Markdown。findings 已排好序（severity → file → line），这里按 severity 分节。 */
final class MarkdownReport {

    private MarkdownReport() {}

    static String render(ReviewReport r) {
        StringBuilder md = new StringBuilder();
        md.append("# PR 审查报告\n\n");
        md.append("- PR: ").append(r.prUrl()).append('\n');
        md.append("- 模式: ").append(r.mode().name().toLowerCase()).append('\n');
        md.append(String.format("- findings: %d（%s）%n", r.findings().size(), severityCounts(r.findings())));
        md.append(String.format("- 失败文件: %d · 跳过文件: %d%n", r.failedFiles().size(), r.skippedFiles().size()));
        md.append(String.format("- 问题追踪: %s%n", errorCounts(r.errors())));
        md.append(String.format("- LLM 调用: %d 次 · in=%d out=%d tokens · 耗时 %.1fs%n",
                r.calls().size(), r.totalInputTokens(), r.totalOutputTokens(), r.totalLatencyMs() / 1000.0));

        for (Severity s : Severity.values()) {
            List<Finding> group = r.findings().stream().filter(f -> f.severity() == s).toList();
            if (group.isEmpty()) {
                continue;
            }
            md.append(String.format("%n## %s (%d)%n%n", s, group.size()));
            for (Finding f : group) {
                md.append(String.format("- **%s:%d** `%s` %s%n", f.file(), f.line(), f.category(), oneLine(f.message())));
                if (f.suggestion() != null && !f.suggestion().isBlank()) {
                    md.append("  - 建议：").append(oneLine(f.suggestion())).append('\n');
                }
            }
        }

        if (!r.errors().isEmpty()) {
            // 按发生顺序列出，每条写明 环节 / 文件 / 调用 / 是否已恢复，可与 stderr 日志中的 label 对照
            md.append(String.format("%n## 问题追踪 (%d)%n%n", r.errors().size()));
            for (ReviewError e : r.errors()) {
                md.append(String.format("- `%s` %s%s%s：%s%n",
                        e.stage(),
                        e.file() == null ? "" : e.file() + " ",
                        e.callLabel() == null || e.callLabel().equals(e.file()) ? "" : "（调用 " + e.callLabel() + "）",
                        e.recovered() ? "已恢复" : "未恢复",
                        oneLine(e.message())));
            }
        }
        if (!r.failedFiles().isEmpty()) {
            md.append("\n## 审查失败的文件\n\n");
            r.failedFiles().forEach(f -> md.append("- ").append(f).append('\n'));
        }
        if (!r.skippedFiles().isEmpty()) {
            md.append("\n## 跳过的文件\n\n");
            for (SkippedFile s : r.skippedFiles()) {
                md.append("- ").append(s.path()).append(" — ").append(s.reason()).append('\n');
            }
        }
        return md.toString();
    }

    static String severityCounts(List<Finding> findings) {
        StringBuilder sb = new StringBuilder();
        for (Severity s : Severity.values()) {
            long n = findings.stream().filter(f -> f.severity() == s).count();
            sb.append(sb.isEmpty() ? "" : " / ").append(s).append(' ').append(n);
        }
        return sb.toString();
    }

    static String errorCounts(List<ReviewError> errors) {
        long unrecovered = errors.stream().filter(e -> !e.recovered()).count();
        return errors.size() + " 条（未恢复 " + unrecovered + "）";
    }

    /** LLM 偶尔在 message 里换行，会打断 Markdown 列表。 */
    private static String oneLine(String s) {
        return s == null ? "" : s.strip().replaceAll("\\s*\\R\\s*", " ");
    }
}
