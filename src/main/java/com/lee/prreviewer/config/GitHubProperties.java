package com.lee.prreviewer.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** application.yml 中 github.* 配置。C# 对照：≈ IOptions&lt;GitHubOptions&gt;。 */
@Validated
@ConfigurationProperties(prefix = "github")
public record GitHubProperties(
        @NotBlank String apiBase,
        @NotBlank(message = "缺少环境变量 GITHUB_TOKEN") String token
) {
    @Override
    public String toString() {
        return "GitHubProperties[apiBase=" + apiBase + "]";
    }
}
