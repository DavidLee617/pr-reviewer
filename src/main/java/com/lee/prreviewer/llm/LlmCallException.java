package com.lee.prreviewer.llm;

import java.util.List;

/**
 * 重试后仍失败的 LLM 调用。携带失败时的计量和每次尝试的错误，原始异常保留在 cause 中。
 * C# 对照：RuntimeException ≈ C# 的普通 Exception（Java 的非受检异常，不需要在方法签名里 throws 声明）；
 * getCause() ≈ InnerException。
 */
public class LlmCallException extends RuntimeException {

    private final CallMetrics metrics;
    private final List<String> attemptErrors;

    public LlmCallException(CallMetrics metrics, Throwable cause, List<String> attemptErrors) {
        super("LLM 调用失败 [" + metrics.label() + "]，共尝试 " + metrics.attempts() + " 次: " + cause.getMessage(), cause);
        this.metrics = metrics;
        this.attemptErrors = List.copyOf(attemptErrors);
    }

    public LlmCallException(CallMetrics metrics, Throwable cause) {
        this(metrics, cause, List.of());
    }

    public CallMetrics metrics() {
        return metrics;
    }

    /** 每次失败尝试的错误（含最后一次），格式见 LlmClient。 */
    public List<String> attemptErrors() {
        return attemptErrors;
    }
}
