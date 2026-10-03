package com.lee.prreviewer.llm;

import java.util.List;

/**
 * 一次成功 LLM 调用的结果：模型输出的文本 + 计量。
 *
 * @param attemptErrors 成功之前失败的各次尝试（已被重试恢复），格式见 LlmClient；一次成功时为空
 */
public record LlmResponse(String content, CallMetrics metrics, List<String> attemptErrors) {

    public LlmResponse {
        attemptErrors = List.copyOf(attemptErrors);
    }

    public LlmResponse(String content, CallMetrics metrics) {
        this(content, metrics, List.of());
    }
}
