package com.lee.prreviewer.map;

import com.lee.prreviewer.map.LlmOutputParser.ParsedFinding;
import com.lee.prreviewer.model.DiffLine;
import com.lee.prreviewer.model.FileDiff;
import com.lee.prreviewer.model.Finding;
import com.lee.prreviewer.model.Hunk;
import com.lee.prreviewer.model.LineType;
import com.lee.prreviewer.model.PrSummary;
import com.lee.prreviewer.model.ReviewError;
import com.lee.prreviewer.model.ReviewStage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Map：单个文件一次 LLM 调用，同时按 SECURITY / LOGIC / PERF / STYLE / NAMING 五套规则审查。
 * 重试由 {@link JsonRetryingCaller} 负责；仍失败时返回 failed 结果，不抛异常，不影响其他文件。
 */
@Component
public class FileReviewer {

    private static final Logger log = LoggerFactory.getLogger(FileReviewer.class);

    private final JsonRetryingCaller caller;
    private final PromptBuilder prompts;

    public FileReviewer(JsonRetryingCaller caller, PromptBuilder prompts) {
        this.caller = caller;
        this.prompts = prompts;
    }

    public FileReviewResult review(FileDiff file, PrSummary summary) {
        JsonRetryingCaller.Result r = caller.call(file.path(), prompts.systemPrompt(),
                prompts.fileUserPrompt(summary, file));
        if (r.failed()) {
            log.error("file_review_failed file={} error={}", file.path(), r.error());
            return new FileReviewResult(file.path(), List.of(), r.calls(), r.error(), r.errors());
        }
        Set<Integer> reviewable = reviewableLines(file);
        List<Finding> findings = new ArrayList<>();
        List<ReviewError> errors = new ArrayList<>(r.errors());
        String callLabel = r.calls().get(r.calls().size() - 1).label(); // findings 来自最后一次（成功解析的）调用
        for (ParsedFinding p : r.findings()) {
            if (!reviewable.contains(p.line())) {
                errors.add(droppedFinding(file.path(), callLabel, p, "行号不在改动范围"));
                continue;
            }
            // file 由代码填入，忽略 LLM 可能输出的 file 字段
            findings.add(new Finding(file.path(), p.line(), p.category(), p.severity(), p.message(), p.suggestion()));
        }
        return new FileReviewResult(file.path(), findings, r.calls(), null, errors);
    }

    /** 被丢弃的 finding：写警告日志，同时返回一条 ReviewError 写进报告。single 模式共用。 */
    public static ReviewError droppedFinding(String file, String callLabel, ParsedFinding p, String reason) {
        log.warn("finding_dropped file={} line={} category={} reason={} message={}",
                file, p.line(), p.category(), reason, p.message());
        // p.file() 是 LLM 填的路径：只有 single 模式要求 LLM 填，map 模式下为空，空时不写，免得被误读为"文件路径丢失"
        String llmFile = p.file() == null || p.file().isBlank() ? "" : "LLM 填的 file=" + p.file() + " ";
        return new ReviewError(ReviewStage.FINDING_VALIDATION, file, callLabel, false,
                "finding 被丢弃（" + reason + "）: " + llmFile + "line=" + p.line() + " category=" + p.category()
                        + " severity=" + p.severity() + " message=" + p.message());
    }

    /**
     * 允许 finding 落在的行：ADDED 行，以及与 ADDED 行相邻的 CONTEXT 行（设计文档 6.5）。
     * "相邻"按 hunk 内顺序计算，跳过中间的 REMOVED 行（它们在新文件中不存在）。
     * single 模式也用它校验行号。
     */
    public static Set<Integer> reviewableLines(FileDiff file) {
        Set<Integer> lines = new HashSet<>();
        for (Hunk h : file.hunks()) {
            // 只保留在新文件中存在的行（ADDED / CONTEXT），按原顺序
            List<DiffLine> present = h.lines().stream().filter(l -> l.type() != LineType.REMOVED).toList();
            for (int i = 0; i < present.size(); i++) {
                DiffLine l = present.get(i);
                boolean added = l.type() == LineType.ADDED;
                boolean nextToAdded = (i > 0 && present.get(i - 1).type() == LineType.ADDED)
                        || (i + 1 < present.size() && present.get(i + 1).type() == LineType.ADDED);
                if (added || nextToAdded) {
                    lines.add(l.newLineNo());
                }
            }
        }
        return lines;
    }
}
