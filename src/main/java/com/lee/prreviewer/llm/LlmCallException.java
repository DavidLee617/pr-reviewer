package com.lee.prreviewer.llm;

/**
 * 重试后仍失败的 LLM 调用。携带失败时的计量，原始异常保留在 cause 中。
 * C# 对照：RuntimeException ≈ C# 的普通 Exception（Java 的非受检异常，不需要在方法签名里 throws 声明）；
 * getCause() ≈ InnerException。
 */
public class LlmCallException extends RuntimeException {

    private final CallMetrics metrics;

    public LlmCallException(CallMetrics metrics, Throwable cause) {
        super("LLM 调用失败 [" + metrics.label() + "]，共尝试 " + metrics.attempts() + " 次: " + cause.getMessage(), cause);
        this.metrics = metrics;
    }

    public CallMetrics metrics() {
        return metrics;
    }
}
