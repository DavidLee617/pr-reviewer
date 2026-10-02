package com.lee.prreviewer.map;

import com.lee.prreviewer.llm.CallMetrics;
import com.lee.prreviewer.llm.LlmCallException;
import com.lee.prreviewer.llm.LlmClient;
import com.lee.prreviewer.llm.LlmResponse;
import com.lee.prreviewer.map.LlmOutputParser.ParsedFinding;
import com.lee.prreviewer.model.DiffLine;
import com.lee.prreviewer.model.FileDiff;
import com.lee.prreviewer.model.Finding;
import com.lee.prreviewer.model.Hunk;
import com.lee.prreviewer.model.LineType;
import com.lee.prreviewer.model.PrSummary;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Map：单个文件一次 LLM 调用，同时按 STYLE / SECURITY / NAMING 三套规则审查。
 * <p>
 * 重试分两层：
 * <ul>
 *   <li>API 错误 / 超时：LlmClient 内部指数退避重试</li>
 *   <li>输出不是合法 JSON 或结构不符：这里带上错误信息再调用 1 次</li>
 * </ul>
 * 仍失败时返回 failed 结果，不抛异常，不影响其他文件。
 */
@Component
public class FileReviewer {

    private static final Logger log = LoggerFactory.getLogger(FileReviewer.class);
    static final String JSON_RETRY_SUFFIX = " [json-retry]";

    private final LlmClient llm;
    private final PromptBuilder prompts;
    private final LlmOutputParser parser;

    public FileReviewer(LlmClient llm, PromptBuilder prompts, LlmOutputParser parser) {
        this.llm = llm;
        this.prompts = prompts;
        this.parser = parser;
    }

    public FileReviewResult review(FileDiff file, PrSummary summary) {
        String system = prompts.systemPrompt();
        String user = prompts.fileUserPrompt(summary, file);
        List<CallMetrics> calls = new ArrayList<>();

        LlmResponse first;
        try {
            first = llm.call(file.path(), system, user);
        } catch (LlmCallException e) {
            calls.add(e.metrics());
            return failed(file, calls, e.getMessage());
        }
        calls.add(first.metrics());
        try {
            return succeeded(file, parser.parse(first.content()), calls);
        } catch (InvalidLlmOutputException firstError) {
            log.warn("llm_output_invalid file={} error={} — 带错误信息重试 1 次", file.path(), firstError.getMessage());

            LlmResponse second;
            try {
                second = llm.call(file.path() + JSON_RETRY_SUFFIX, system,
                        prompts.jsonRetryPrompt(user, first.content(), firstError.getMessage()));
            } catch (LlmCallException e) {
                calls.add(e.metrics());
                return failed(file, calls, "JSON 重试时 LLM 调用失败: " + e.getMessage());
            }
            calls.add(second.metrics());
            try {
                return succeeded(file, parser.parse(second.content()), calls);
            } catch (InvalidLlmOutputException secondError) {
                return failed(file, calls, "LLM 输出两次均无法解析: " + firstError.getMessage()
                        + " / " + secondError.getMessage());
            }
        }
    }

    private static FileReviewResult succeeded(FileDiff file, List<ParsedFinding> parsed, List<CallMetrics> calls) {
        Set<Integer> reviewable = reviewableLines(file);
        List<Finding> findings = new ArrayList<>();
        for (ParsedFinding p : parsed) {
            if (!reviewable.contains(p.line())) {
                log.warn("finding_dropped file={} line={} category={} reason=行号不在改动范围 message={}",
                        file.path(), p.line(), p.category(), p.message());
                continue;
            }
            // file 由代码填入，忽略 LLM 可能输出的 file 字段
            findings.add(new Finding(file.path(), p.line(), p.category(), p.severity(), p.message(), p.suggestion()));
        }
        return new FileReviewResult(file.path(), findings, calls, null);
    }

    private static FileReviewResult failed(FileDiff file, List<CallMetrics> calls, String error) {
        log.error("file_review_failed file={} error={}", file.path(), error);
        return new FileReviewResult(file.path(), List.of(), calls, error);
    }

    /**
     * 允许 finding 落在的行：ADDED 行，以及与 ADDED 行相邻的 CONTEXT 行（设计文档 6.5）。
     * "相邻"按 hunk 内顺序计算，跳过中间的 REMOVED 行（它们在新文件中不存在）。
     */
    static Set<Integer> reviewableLines(FileDiff file) {
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
