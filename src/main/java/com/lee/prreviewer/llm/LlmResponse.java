package com.lee.prreviewer.llm;

/** 一次成功 LLM 调用的结果：模型输出的文本 + 计量。 */
public record LlmResponse(String content, CallMetrics metrics) {}
