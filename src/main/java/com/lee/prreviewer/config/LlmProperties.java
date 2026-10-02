package com.lee.prreviewer.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * application.yml 中 llm.* 配置。
 * C# 对照：≈ 绑定到 IOptions&lt;LlmOptions&gt; 的 Options 类，
 * {@code @Validated} + {@code @NotBlank} ≈ [Required] + ValidateDataAnnotations().ValidateOnStart()，
 * 缺少配置时启动即失败退出。
 */
@Validated
@ConfigurationProperties(prefix = "llm")
public record LlmProperties(
        @NotBlank(message = "缺少环境变量 LLM_BASE_URL") String baseUrl,
        @NotBlank(message = "缺少环境变量 LLM_API_KEY") String apiKey,
        @NotBlank(message = "缺少环境变量 LLM_MODEL") String model,
        @Positive int timeoutSeconds
) {
    @Override
    public String toString() {
        // 防止 api-key 出现在日志里
        return "LlmProperties[baseUrl=" + baseUrl + ", model=" + model + ", timeoutSeconds=" + timeoutSeconds + "]";
    }
}
