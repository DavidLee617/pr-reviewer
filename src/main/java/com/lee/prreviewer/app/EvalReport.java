package com.lee.prreviewer.app;

import com.lee.prreviewer.eval.EvalResult;
import com.lee.prreviewer.eval.EvalResult.BugResult;
import com.lee.prreviewer.eval.EvalResult.Ratio;
import com.lee.prreviewer.eval.EvalResult.Segment;
import com.lee.prreviewer.model.Finding;
import com.lee.prreviewer.model.Severity;
import java.util.List;
import java.util.Map;

/** 把 EvalResult 渲染成给人看的 Markdown。 */
final class EvalReport {

    private static final Map<Segment, String> SEGMENT_NAMES =
            Map.of(Segment.FRONT, "前段", Segment.MIDDLE, "中段", Segment.BACK, "后段");

    private EvalReport() {}

    static String render(EvalResult r, String reportPath, String truthPath) {
        StringBuilder md = new StringBuilder("# 召回评估\n\n");
        md.append("- 报告: ").append(reportPath).append("（").append(r.mode().name().toLowerCase()).append("）\n");
        md.append("- 标准答案: ").append(truthPath).append("（").append(r.bugs().size()).append(" 条）\n");
        md.append("- PR: ").append(r.prUrl()).append('\n');
        md.append(String.format("- 命中规则: 同文件且 line ∈ [lineStart - %d, lineEnd + %d]，类别不要求一致%n",
                r.lineTolerance(), r.lineTolerance()));

        if (!r.warnings().isEmpty()) {
            md.append("\n## ⚠ 警告\n\n");
            r.warnings().forEach(w -> md.append("- ").append(w).append('\n'));
        }

        md.append("\n## 召回\n\n| 口径 | 命中 / 总数 | 召回 |\n|---|---|---|\n");
        row(md, "全部", r.overall());
        row(md, "审查范围内（未被过滤规则跳过）", r.inScope());
        row(md, "单文件雷", r.singleFile());
        row(md, "跨文件雷", r.crossFile());
        for (Severity s : Severity.values()) {
            row(md, s.name(), r.bySeverity().get(s));
        }
        md.append(String.format("%n类别一致率（命中的埋点中）: %d / %d（%s）%n",
                r.categoryAgreement().hit(), r.categoryAgreement().total(), pct(r.categoryAgreement())));
        md.append(String.format("未匹配任何埋点的 finding: %d（仅参考，不等于误报）%n", r.unmatchedFindings().size()));

        md.append("\n## 分段召回（按 PR 文件列表顺序三等分）\n\n| 分段 | 命中 / 总数 | 召回 | 文件 |\n|---|---|---|---|\n");
        for (Segment s : Segment.values()) {
            Ratio ratio = r.bySegment().get(s);
            List<String> files = r.segmentFiles().get(s);
            md.append(String.format("| %s | %d / %d | %s | %d 个：%s |%n", SEGMENT_NAMES.get(s), ratio.hit(), ratio.total(),
                    pct(ratio), files.size(), String.join("、", files.stream().map(EvalReport::shortName).toList())));
        }

        md.append("\n## 每个埋点\n\n");
        for (BugResult b : r.bugs()) {
            md.append(String.format("- %s **%s** %s%s%s · %s%n",
                    b.hit() ? "✓" : "✗",
                    b.id(),
                    b.severity(),
                    b.crossFile() ? " · 跨文件" : "",
                    b.segment() == null ? "" : " · " + SEGMENT_NAMES.get(b.segment()),
                    b.inScope() ? b.note() : b.note() + "（不在审查范围）"));
            for (Finding f : b.matched()) {
                md.append(String.format("  - %s:%d `%s` %s%n", shortName(f.file()), f.line(), f.category(), oneLine(f.message())));
            }
            if (b.hit() && !b.categoryMatched()) {
                md.append("  - （类别不一致）\n");
            }
        }

        if (!r.unmatchedFindings().isEmpty()) {
            md.append("\n## 未匹配任何埋点的 finding\n\n");
            for (Finding f : r.unmatchedFindings()) {
                md.append(String.format("- %s:%d `%s` %s %s%n",
                        shortName(f.file()), f.line(), f.category(), f.severity(), oneLine(f.message())));
            }
        }
        return md.toString();
    }

    private static void row(StringBuilder md, String name, Ratio r) {
        md.append(String.format("| %s | %d / %d | %s |%n", name, r.hit(), r.total(), pct(r)));
    }

    private static String pct(Ratio r) {
        return r.total() == 0 ? "—" : String.format("%.1f%%", r.rate() * 100);
    }

    private static String shortName(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.strip().replaceAll("\\s*\\R\\s*", " ");
    }
}
