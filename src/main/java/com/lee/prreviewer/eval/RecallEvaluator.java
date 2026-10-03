package com.lee.prreviewer.eval;

import com.lee.prreviewer.eval.EvalResult.BugResult;
import com.lee.prreviewer.eval.EvalResult.Ratio;
import com.lee.prreviewer.eval.EvalResult.Segment;
import com.lee.prreviewer.eval.GroundTruth.Location;
import com.lee.prreviewer.eval.GroundTruth.SeededBug;
import com.lee.prreviewer.model.Finding;
import com.lee.prreviewer.model.ReviewReport;
import com.lee.prreviewer.model.ReviewStage;
import com.lee.prreviewer.model.Severity;
import com.lee.prreviewer.model.SkippedFile;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import org.springframework.stereotype.Component;

/**
 * 对照标准答案计算召回（纯代码，设计文档第 9 节）。
 * <p>
 * 候选：finding 的 file 与埋点任一位置相同，且 line ∈ [lineStart - 容差, lineEnd + 容差]。
 * 命中：候选之间一对一匹配（见 {@link #assign}），一条 finding 最多命中一个埋点。
 * 类别不影响命中，单独统计一致率。"问题本质是否一致"代码判断不了，命中的 finding 原文列在结果里供人工复核。
 */
@Component
public class RecallEvaluator {

    /** 行号容差，按埋雷文档的口径为 ±3。 */
    public static final int LINE_TOLERANCE = 3;

    public EvalResult evaluate(ReviewReport report, GroundTruth truth) {
        List<String> warnings = warnings(report, truth);
        Set<String> skipped = new HashSet<>();
        report.skippedFiles().stream().map(SkippedFile::path).forEach(skipped::add);
        List<String> prFiles = report.prFiles();

        Map<Integer, Finding> assignment = assign(truth.bugs(), report.findings());
        List<BugResult> bugs = new ArrayList<>();
        for (int b = 0; b < truth.bugs().size(); b++) {
            SeededBug bug = truth.bugs().get(b);
            List<Finding> matched = assignment.containsKey(b) ? List.of(assignment.get(b)) : List.of();
            boolean inScope = bug.locations().stream().anyMatch(l -> prFiles.contains(l.file()) && !skipped.contains(l.file()));
            Segment segment = segmentOf(bug.locations().get(0).file(), prFiles);
            if (segment == null) {
                warnings.add(bug.id() + " 的文件 " + bug.locations().get(0).file() + " 不在报告的 PR 文件列表中，无法分段");
            }
            boolean categoryMatched = matched.stream().anyMatch(f -> bug.categories().contains(f.category()));
            bugs.add(new BugResult(bug.id(), bug.severity(), bug.crossFile(), inScope, segment,
                    !matched.isEmpty(), categoryMatched, matched, bug.note()));
        }

        Map<Severity, Ratio> bySeverity = new EnumMap<>(Severity.class);
        for (Severity s : Severity.values()) {
            bySeverity.put(s, ratio(bugs, b -> b.severity() == s));
        }
        Map<Segment, Ratio> bySegment = new EnumMap<>(Segment.class);
        Map<Segment, List<String>> segmentFiles = new EnumMap<>(Segment.class);
        for (Segment s : Segment.values()) {
            bySegment.put(s, ratio(bugs, b -> b.segment() == s));
            segmentFiles.put(s, prFiles.stream().filter(f -> segmentOf(f, prFiles) == s).toList());
        }
        List<BugResult> hitBugs = bugs.stream().filter(BugResult::hit).toList();
        Ratio categoryAgreement = new Ratio((int) hitBugs.stream().filter(BugResult::categoryMatched).count(), hitBugs.size());
        Set<Finding> used = new HashSet<>(assignment.values());
        List<Finding> unmatched = report.findings().stream().filter(f -> !used.contains(f)).toList();

        return new EvalResult(report.mode(), report.prUrl(), LINE_TOLERANCE, warnings,
                ratio(bugs, b -> true), ratio(bugs, BugResult::inScope),
                ratio(bugs, b -> !b.crossFile()), ratio(bugs, BugResult::crossFile),
                bySeverity, bySegment, segmentFiles, categoryAgreement, bugs, unmatched);
    }

    /**
     * 一对一匹配：一条 finding 最多命中一个埋点，一个埋点最多记一条 finding。
     * <p>
     * 埋点密集时 ±容差 的窗口会重叠，若允许一条 finding 命中多个埋点，召回会被高估
     * （PR #1 实测：一条"库存 &lt;="的 finding 同时"命中"了相邻的拆箱 NPE 埋点）。
     * 做法：所有候选配对按 类别是否一致 → 行距 → 埋点顺序 → finding 顺序 排序，依次配对，已用过的跳过。
     * 类别优先于行距：答案的类别是人工给的且允许多个，比 1～2 行的距离差更能说明"说的是同一个问题"
     * （PR #1 实测：按行距优先时，"返回 User 实体"的埋点配到了相邻行"System.out 代替日志"的 finding）。
     * 副作用：类别一致率是"尽量配到类别一致的"之后的结果，偏乐观。
     *
     * @return 埋点下标 → 配到的 finding
     */
    static Map<Integer, Finding> assign(List<SeededBug> bugs, List<Finding> findings) {
        record Candidate(int bug, int finding, int distance, boolean categoryMismatch) {}
        List<Candidate> candidates = new ArrayList<>();
        for (int b = 0; b < bugs.size(); b++) {
            for (int f = 0; f < findings.size(); f++) {
                int d = distance(findings.get(f), bugs.get(b));
                if (d <= LINE_TOLERANCE) {
                    boolean mismatch = !bugs.get(b).categories().contains(findings.get(f).category());
                    candidates.add(new Candidate(b, f, d, mismatch));
                }
            }
        }
        candidates.sort(Comparator.comparing(Candidate::categoryMismatch)
                .thenComparingInt(Candidate::distance)
                .thenComparingInt(Candidate::bug)
                .thenComparingInt(Candidate::finding));

        Map<Integer, Finding> assignment = new HashMap<>();
        Set<Integer> usedFindings = new HashSet<>();
        for (Candidate c : candidates) {
            if (!assignment.containsKey(c.bug()) && usedFindings.add(c.finding())) {
                assignment.put(c.bug(), findings.get(c.finding()));
            }
        }
        return assignment;
    }

    /** 是否落在埋点任一位置的 ±容差 范围内（不考虑一对一）。 */
    static boolean hits(Finding f, SeededBug bug) {
        return distance(f, bug) <= LINE_TOLERANCE;
    }

    /** finding 到埋点最近位置的行距：同文件且在 [lineStart, lineEnd] 内为 0；不同文件为 Integer.MAX_VALUE。 */
    static int distance(Finding f, SeededBug bug) {
        int best = Integer.MAX_VALUE;
        for (Location l : bug.locations()) {
            if (!l.file().equals(f.file())) {
                continue;
            }
            int d = f.line() < l.lineStart() ? l.lineStart() - f.line()
                    : f.line() > l.lineEnd() ? f.line() - l.lineEnd() : 0;
            best = Math.min(best, d);
        }
        return best;
    }

    /** 按文件在 PR 文件列表中的下标三等分；不在列表中返回 null。 */
    static Segment segmentOf(String file, List<String> prFiles) {
        int i = prFiles.indexOf(file);
        if (i < 0) {
            return null;
        }
        return Segment.values()[i * 3 / prFiles.size()];
    }

    private static Ratio ratio(List<BugResult> bugs, Predicate<BugResult> filter) {
        List<BugResult> selected = bugs.stream().filter(filter).toList();
        return new Ratio((int) selected.stream().filter(BugResult::hit).count(), selected.size());
    }

    /** 会影响结论可信度的情况，放在结果最前面。 */
    private static List<String> warnings(ReviewReport report, GroundTruth truth) {
        List<String> w = new ArrayList<>();
        if (truth.prUrl() != null && !truth.prUrl().equals(report.prUrl())) {
            w.add("报告的 PR（" + report.prUrl() + "）与标准答案的 PR（" + truth.prUrl() + "）不同");
        }
        if (truth.headSha() != null && report.headSha() != null && !report.headSha().startsWith(truth.headSha())
                && !truth.headSha().startsWith(report.headSha())) {
            w.add("报告审查的提交 " + report.headSha() + " 与标准答案依据的提交 " + truth.headSha() + " 不同，行号可能已偏移");
        }
        if (report.prFiles().isEmpty()) {
            w.add("报告中没有 PR 文件列表（旧版报告或预处理失败），无法分段");
        }
        if (report.errors().stream().anyMatch(e -> e.stage() == ReviewStage.PREPARE)) {
            w.add("报告的预处理失败，没有任何 finding");
        }
        if (!report.failedFiles().isEmpty()) {
            w.add("报告中有 " + report.failedFiles().size() + " 个文件审查失败: " + report.failedFiles());
        }
        Set<String> ids = new LinkedHashSet<>();
        truth.bugs().stream().map(SeededBug::id).filter(Objects::nonNull).filter(id -> !ids.add(id))
                .forEach(id -> w.add("标准答案中 id 重复: " + id));
        return w;
    }
}
