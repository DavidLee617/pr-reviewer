package com.lee.prreviewer.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
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
        @Positive int timeoutSeconds,
        // 固定为 0：同一 PR 多次审查结果尽量一致，两种模式的召回对比才有意义（未设置时同一 PR 两次结果条数不同）
        @DecimalMin("0.0") @DecimalMax("2.0") double temperature
) {
    @Override
    public String toString() {
        // 防止 api-key 出现在日志里
        return "LlmProperties[baseUrl=" + baseUrl + ", model=" + model + ", timeoutSeconds=" + timeoutSeconds
                + ", temperature=" + temperature + "]";
    }
}
