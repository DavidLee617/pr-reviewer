package com.lee.prreviewer.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpResponse;

/**
 * GitHub API 返回错误（401 / 403 / 404 / 速率限制等）。信息里带 HTTP 状态码和 GitHub 返回的 message。
 * C# 对照：≈ 自定义的 HttpRequestException，statusCode() ≈ StatusCode 属性。
 */
public class GitHubApiException extends RuntimeException {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final int statusCode;

    public GitHubApiException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }

    /** RestClient 的错误处理回调里调用，把响应翻译成明确的异常。 */
    static GitHubApiException from(HttpRequest request, ClientHttpResponse response) throws IOException {
        int status = response.getStatusCode().value();
        String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
        String remaining = response.getHeaders().getFirst("x-ratelimit-remaining");

        String hint;
        if ((status == 403 || status == 429) && "0".equals(remaining)) {
            String reset = response.getHeaders().getFirst("x-ratelimit-reset");
            hint = "GitHub 速率限制已用尽" + (reset == null ? "" : "，重置时间 " + Instant.ofEpochSecond(Long.parseLong(reset)));
        } else {
            hint = switch (status) {
                case 401 -> "token 无效或已过期（检查 GITHUB_TOKEN）";
                case 403 -> "无权限（token 权限不足，或触发了次级速率限制）";
                case 404 -> "仓库或 PR 不存在，或 token 无权访问该仓库";
                case 429 -> "请求过多（速率限制）";
                default -> "GitHub API 错误";
            };
        }
        return new GitHubApiException(status,
                hint + " — HTTP " + status + " " + request.getMethod() + " " + request.getURI()
                        + " — GitHub message: " + extractMessage(body));
    }

    private static String extractMessage(String body) {
        try {
            String msg = JSON.readTree(body).path("message").asText("");
            return msg.isEmpty() ? body : msg;
        } catch (IOException e) {
            return body;
        }
    }
}
