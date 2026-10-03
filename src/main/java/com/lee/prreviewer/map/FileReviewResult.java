package com.lee.prreviewer.map;

import com.lee.prreviewer.llm.CallMetrics;
import com.lee.prreviewer.model.Finding;
import com.lee.prreviewer.model.ReviewError;
import java.util.List;

/**
 * 单个文件的 map 结果（single 模式下 file 为 "single"）。
 *
 * @param calls  本文件产生的所有 LLM 调用（JSON 重试会多一条）
 * @param error  失败原因摘要；成功时为 null
 * @param errors 过程中的全部问题（含已恢复的、被丢弃的 finding），写进报告用于溯源
 */
public record FileReviewResult(String file, List<Finding> findings, List<CallMetrics> calls, String error,
                               List<ReviewError> errors) {

    public FileReviewResult {
        findings = List.copyOf(findings);
        calls = List.copyOf(calls);
        errors = List.copyOf(errors);
    }

    public FileReviewResult(String file, List<Finding> findings, List<CallMetrics> calls, String error) {
        this(file, findings, calls, error, List.of());
    }

    public boolean failed() {
        return error != null;
    }
}
