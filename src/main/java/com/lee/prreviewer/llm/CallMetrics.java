package com.lee.prreviewer.llm;

/**
 * 一次逻辑 LLM 调用的计量（含内部重试）。
 *
 * @param label        文件路径，或 "single"
 * @param inputTokens  取自 API 返回的 usage，不自行估算
 * @param outputTokens 取自 API 返回的 usage，不自行估算
 * @param latencyMs    从第一次尝试开始到最终成功/放弃的耗时（含退避等待）
 * @param attempts     实际尝试次数（1 = 一次成功）
 */
public record CallMetrics(
        String label,
        int inputTokens,
        int outputTokens,
        long latencyMs,
        boolean success,
        int attempts
) {}
