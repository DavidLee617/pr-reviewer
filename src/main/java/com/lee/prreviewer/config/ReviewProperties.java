package com.lee.prreviewer.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * application.yml 中 review.* 配置。yml 里的 kebab-case（max-retries）自动映射到 camelCase 字段。
 * C# 对照：≈ IOptions&lt;ReviewOptions&gt;，嵌套 record 对应嵌套的配置节。
 */
@Validated
@ConfigurationProperties(prefix = "review")
public record ReviewProperties(
        @Positive int concurrency,
        @Min(0) int maxRetries,
        @Positive int summaryMaxTokens,
        @Valid @NotNull Filter filter
) {
    /**
     * review.filter.*
     *
     * @param includeGlobs 只审查匹配这些规则的文件（默认只审 Java 源文件）
     * @param excludeGlobs 即使匹配 include 也要排除的文件（构建产物、自动生成代码）
     */
    public record Filter(@NotNull List<String> includeGlobs, @NotNull List<String> excludeGlobs) {}
}
