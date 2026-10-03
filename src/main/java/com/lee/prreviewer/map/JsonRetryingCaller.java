package com.lee.prreviewer.map;

import com.lee.prreviewer.llm.CallMetrics;
import com.lee.prreviewer.llm.LlmCallException;
import com.lee.prreviewer.llm.LlmClient;
import com.lee.prreviewer.llm.LlmResponse;
import com.lee.prreviewer.map.LlmOutputParser.ParsedFinding;
import com.lee.prreviewer.model.ReviewError;
import com.lee.prreviewer.model.ReviewStage;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 一次审查调用 + 解析 findings JSON，map 和 single 两种模式共用，保证重试策略相同：
 * <ul>
 *   <li>API 错误 / 超时：LlmClient 内部指数退避重试</li>
 *   <li>输出不是合法 JSON 或结构不符：这里带上错误信息再调用 1 次</li>
 * </ul>
 * 仍失败时返回带 error 的结果，不抛异常。每次失败的尝试和每次解析失败都记一条 ReviewError（含已恢复的）。
 */
@Component
public class JsonRetryingCaller {

    private static final Logger log = LoggerFactory.getLogger(JsonRetryingCaller.class);
    public static final String JSON_RETRY_SUFFIX = " [json-retry]";
    private static final int OUTPUT_PREVIEW_CHARS = 300;

    /**
     * @param calls  本次产生的所有 LLM 调用（JSON 重试会多一条）
     * @param error  失败原因摘要；成功时为 null
     * @param errors 过程中的全部问题，按发生顺序
     */
    public record Result(List<ParsedFinding> findings, List<CallMetrics> calls, String error, List<ReviewError> errors) {
        public boolean failed() {
            return error != null;
        }
    }

    private final LlmClient llm;
    private final PromptBuilder prompts;
    private final LlmOutputParser parser;

    public JsonRetryingCaller(LlmClient llm, PromptBuilder prompts, LlmOutputParser parser) {
        this.llm = llm;
        this.prompts = prompts;
        this.parser = parser;
    }

    /** @param label 计量标签，同时作为 ReviewError.file：map 模式为文件路径，single 模式为 "single" */
    public Result call(String label, String system, String user) {
        List<CallMetrics> calls = new ArrayList<>();
        List<ReviewError> errors = new ArrayList<>();

        LlmResponse first;
        try {
            first = llm.call(label, system, user);
        } catch (LlmCallException e) {
            calls.add(e.metrics());
            addCallFailure(errors, label, label, e);
            return new Result(List.of(), calls, e.getMessage(), errors);
        }
        calls.add(first.metrics());
        addRecoveredAttempts(errors, label, label, first);
        try {
            return new Result(parser.parse(first.content()), calls, null, errors);
        } catch (InvalidLlmOutputException firstError) {
            log.warn("llm_output_invalid label={} error={} — 带错误信息重试 1 次", label, firstError.getMessage());
            String retryLabel = label + JSON_RETRY_SUFFIX;
            // 首次解析失败是否"已恢复"要等重试结果才知道，先记住位置，保证 errors 按发生顺序
            int firstErrorAt = errors.size();

            LlmResponse second;
            try {
                second = llm.call(retryLabel, system, prompts.jsonRetryPrompt(user, first.content(), firstError.getMessage()));
            } catch (LlmCallException e) {
                calls.add(e.metrics());
                addCallFailure(errors, label, retryLabel, e);
                errors.add(firstErrorAt, outputError(label, label, false, firstError, first.content()));
                return new Result(List.of(), calls, "JSON 重试时 LLM 调用失败: " + e.getMessage(), errors);
            }
            calls.add(second.metrics());
            addRecoveredAttempts(errors, label, retryLabel, second);
            try {
                List<ParsedFinding> parsed = parser.parse(second.content());
                errors.add(firstErrorAt, outputError(label, label, true, firstError, first.content()));
                return new Result(parsed, calls, null, errors);
            } catch (InvalidLlmOutputException secondError) {
                errors.add(firstErrorAt, outputError(label, label, false, firstError, first.content()));
                errors.add(outputError(label, retryLabel, false, secondError, second.content()));
                return new Result(List.of(), calls, "LLM 输出两次均无法解析: " + firstError.getMessage()
                        + " / " + secondError.getMessage(), errors);
            }
        }
    }

    /** 调用最终失败：每次失败尝试一条；没有尝试明细时（如测试替身）用异常信息兜底。 */
    private static void addCallFailure(List<ReviewError> errors, String file, String callLabel, LlmCallException e) {
        if (e.attemptErrors().isEmpty()) {
            errors.add(new ReviewError(ReviewStage.LLM_CALL, file, callLabel, false, ReviewError.describe(e)));
            return;
        }
        e.attemptErrors().forEach(msg -> errors.add(new ReviewError(ReviewStage.LLM_CALL, file, callLabel, false, msg)));
    }

    /** 调用最终成功，但之前有失败的尝试：记为已恢复。 */
    private static void addRecoveredAttempts(List<ReviewError> errors, String file, String callLabel, LlmResponse r) {
        r.attemptErrors().forEach(msg -> errors.add(new ReviewError(ReviewStage.LLM_CALL, file, callLabel, true, msg)));
    }

    /** 解析失败：附上输出开头，便于判断是被截断、多了说明文字，还是字段取值不对。 */
    private static ReviewError outputError(String file, String callLabel, boolean recovered,
                                           InvalidLlmOutputException e, String output) {
        String preview = output == null ? "" : output.strip();
        if (preview.length() > OUTPUT_PREVIEW_CHARS) {
            preview = preview.substring(0, OUTPUT_PREVIEW_CHARS) + "…";
        }
        return new ReviewError(ReviewStage.LLM_OUTPUT, file, callLabel, recovered,
                e.getMessage() + "；输出开头: " + preview);
    }
}
