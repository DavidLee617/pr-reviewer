package com.lee.prreviewer.map;

import com.lee.prreviewer.llm.CallMetrics;
import com.lee.prreviewer.model.Finding;
import java.util.List;

/**
 * 单个文件的 map 结果。
 *
 * @param calls 本文件产生的所有 LLM 调用（JSON 重试会多一条）
 * @param error 失败原因；成功时为 null
 */
public record FileReviewResult(String file, List<Finding> findings, List<CallMetrics> calls, String error) {

    public FileReviewResult {
        findings = List.copyOf(findings);
        calls = List.copyOf(calls);
    }

    public boolean failed() {
        return error != null;
    }
}
