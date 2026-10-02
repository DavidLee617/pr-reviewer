package com.lee.prreviewer.model;

import com.lee.prreviewer.llm.CallMetrics;
import java.util.List;

/**
 * 最终审查报告，--out 时序列化为 JSON（Jackson 原生支持 record）。
 * C# 对照：Jackson ≈ System.Text.Json，record 组件名即 JSON 字段名（camelCase）。
 *
 * @param findings       已去重排序
 * @param failedFiles    重试后仍失败的文件
 * @param totalLatencyMs 墙钟时间（并发调用时小于各调用耗时之和）
 */
public record ReviewReport(
        ReviewMode mode,
        String prUrl,
        List<Finding> findings,
        List<String> failedFiles,
        List<SkippedFile> skippedFiles,
        List<CallMetrics> calls,
        long totalLatencyMs,
        int totalInputTokens,
        int totalOutputTokens
) {
    public ReviewReport {
        findings = List.copyOf(findings);
        failedFiles = List.copyOf(failedFiles);
        skippedFiles = List.copyOf(skippedFiles);
        calls = List.copyOf(calls);
    }
}
