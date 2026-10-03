package com.lee.prreviewer.model;

import com.lee.prreviewer.llm.CallMetrics;
import java.util.List;

/**
 * 最终审查报告，--out 时序列化为 JSON（Jackson 原生支持 record）。
 * C# 对照：Jackson ≈ System.Text.Json，record 组件名即 JSON 字段名（camelCase）。
 *
 * @param headSha        审查的 PR head commit；预处理失败时为 null。评估时用来核对和标准答案是否针对同一提交
 * @param prFiles        PR 全部文件（含被跳过的），GitHub 返回的顺序；评估按它分前 / 中 / 后三段
 * @param findings       已去重排序
 * @param failedFiles    重试后仍失败的文件（single 模式整次调用失败时为全部待审文件）
 * @param errors         过程中出现的全部问题（含已恢复的），按发生环节溯源；预处理失败时只有这一项有内容
 * @param totalLatencyMs 墙钟时间（并发调用时小于各调用耗时之和）
 */
public record ReviewReport(
        ReviewMode mode,
        String prUrl,
        String headSha,
        List<String> prFiles,
        List<Finding> findings,
        List<String> failedFiles,
        List<SkippedFile> skippedFiles,
        List<ReviewError> errors,
        List<CallMetrics> calls,
        long totalLatencyMs,
        int totalInputTokens,
        int totalOutputTokens
) {
    public ReviewReport {
        // 允许缺失（读取旧版本报告 JSON 时，后来新增的字段为 null）
        prFiles = copy(prFiles);
        findings = copy(findings);
        failedFiles = copy(failedFiles);
        skippedFiles = copy(skippedFiles);
        errors = copy(errors);
        calls = copy(calls);
    }

    private static <T> List<T> copy(List<T> list) {
        return list == null ? List.of() : List.copyOf(list);
    }
}
