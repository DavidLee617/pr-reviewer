package com.lee.prreviewer.eval;

import com.lee.prreviewer.model.Finding;
import com.lee.prreviewer.model.ReviewMode;
import com.lee.prreviewer.model.Severity;
import java.util.List;
import java.util.Map;

/**
 * 召回评估结果（设计文档第 9 节）。
 *
 * @param warnings          报告与答案不匹配（PR、提交不同）、报告本身有失败等，会影响结论可信度的情况
 * @param inScope           文件在审查范围内（未被过滤规则跳过）的埋点召回
 * @param bySegment         按主位置文件在 PR 文件列表中的位置分前 / 中 / 后三段
 * @param categoryAgreement 命中的埋点中，至少一条命中 finding 的类别属于该埋点可接受类别的比例
 * @param unmatchedFindings 未匹配任何埋点的 finding（仅参考，不等于误报）
 */
public record EvalResult(
        ReviewMode mode,
        String prUrl,
        int lineTolerance,
        List<String> warnings,
        Ratio overall,
        Ratio inScope,
        Ratio singleFile,
        Ratio crossFile,
        Map<Severity, Ratio> bySeverity,
        Map<Segment, Ratio> bySegment,
        Map<Segment, List<String>> segmentFiles,
        Ratio categoryAgreement,
        List<BugResult> bugs,
        List<Finding> unmatchedFindings
) {

    public enum Segment { FRONT, MIDDLE, BACK }

    public record Ratio(int hit, int total) {
        public double rate() {
            return total == 0 ? 0 : (double) hit / total;
        }
    }

    /**
     * 单个埋点的评估。
     *
     * @param inScope         至少一个位置的文件在审查范围内
     * @param segment         主位置所在分段；文件不在 PR 文件列表中时为 null
     * @param matched         命中它的 finding（供人工复核"问题本质是否一致"）
     * @param categoryMatched 命中的 finding 中至少一条类别属于可接受类别
     */
    public record BugResult(String id, Severity severity, boolean crossFile, boolean inScope, Segment segment,
                            boolean hit, boolean categoryMatched, List<Finding> matched, String note) {}
}
