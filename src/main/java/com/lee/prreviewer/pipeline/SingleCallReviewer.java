package com.lee.prreviewer.pipeline;

import com.lee.prreviewer.map.FileReviewResult;
import com.lee.prreviewer.map.FileReviewer;
import com.lee.prreviewer.map.JsonRetryingCaller;
import com.lee.prreviewer.map.LlmOutputParser.ParsedFinding;
import com.lee.prreviewer.map.PromptBuilder;
import com.lee.prreviewer.model.FileDiff;
import com.lee.prreviewer.model.Finding;
import com.lee.prreviewer.model.PrSummary;
import com.lee.prreviewer.model.ReviewError;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Baseline：整个 PR 一次 LLM 调用（设计文档 6.7），用于和 mapreduce 对比。
 * <p>
 * 超出模型上下文时不截断：调用直接失败（4xx 不重试），失败本身就是实验结果。
 * 重试策略与 map 相同（{@link JsonRetryingCaller}）。
 */
@Component
public class SingleCallReviewer {

    private static final Logger log = LoggerFactory.getLogger(SingleCallReviewer.class);
    public static final String LABEL = "single";

    private final JsonRetryingCaller caller;
    private final PromptBuilder prompts;

    public SingleCallReviewer(JsonRetryingCaller caller, PromptBuilder prompts) {
        this.caller = caller;
        this.prompts = prompts;
    }

    /** @return file 字段为 {@link #LABEL} 的结果；findings 已按各自文件的改动范围校验 */
    public FileReviewResult review(List<FileDiff> files, PrSummary summary) {
        if (files.isEmpty()) {
            return new FileReviewResult(LABEL, List.of(), List.of(), null);
        }
        JsonRetryingCaller.Result r = caller.call(LABEL, prompts.systemPrompt(), prompts.singleUserPrompt(summary, files));
        if (r.failed()) {
            log.error("single_review_failed files={} error={}", files.size(), r.error());
            return new FileReviewResult(LABEL, List.of(), r.calls(), r.error(), r.errors());
        }
        List<Finding> findings = new ArrayList<>();
        List<ReviewError> errors = new ArrayList<>(r.errors());
        String callLabel = r.calls().get(r.calls().size() - 1).label(); // findings 来自最后一次（成功解析的）调用
        for (ParsedFinding p : r.findings()) {
            Optional<FileDiff> file = resolve(p.file(), files);
            if (file.isEmpty()) {
                errors.add(FileReviewer.droppedFinding(LABEL, callLabel, p, "file 不是本次审查的文件"));
                continue;
            }
            Set<Integer> reviewable = FileReviewer.reviewableLines(file.get());
            if (!reviewable.contains(p.line())) {
                errors.add(FileReviewer.droppedFinding(file.get().path(), callLabel, p, "行号不在改动范围"));
                continue;
            }
            findings.add(new Finding(file.get().path(), p.line(), p.category(), p.severity(), p.message(), p.suggestion()));
        }
        return new FileReviewResult(LABEL, findings, r.calls(), null, errors);
    }

    /** LLM 填的 file：完全相同优先；否则接受唯一匹配的路径后缀（如 service/OrderService.java），并补全为完整路径。 */
    static Optional<FileDiff> resolve(String file, List<FileDiff> files) {
        if (file == null || file.isBlank()) {
            return Optional.empty();
        }
        String f = file.strip();
        Optional<FileDiff> exact = files.stream().filter(d -> d.path().equals(f)).findFirst();
        if (exact.isPresent()) {
            return exact;
        }
        List<FileDiff> suffix = files.stream().filter(d -> d.path().endsWith("/" + f)).toList();
        return suffix.size() == 1 ? Optional.of(suffix.get(0)) : Optional.empty();
    }
}
