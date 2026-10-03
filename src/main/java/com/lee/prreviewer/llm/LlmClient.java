package com.lee.prreviewer.llm;

import com.lee.prreviewer.config.LlmProperties;
import com.lee.prreviewer.config.ReviewProperties;
import com.lee.prreviewer.model.ReviewError;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 所有 LLM 调用的统一封装：调用 + 重试 + 计量 + 结构化日志。
 * <p>
 * 重试分工：本类只重试 API 错误 / 超时（指数退避，最多 review.max-retries 次）；
 * "返回不是合法 JSON"的重试需要改 prompt，由 FileReviewer 负责（M3）。
 * <p>
 * C# 对照：{@code @Component} ≈ services.AddSingleton&lt;LlmClient&gt;()，构造函数注入与 ASP.NET Core 相同；
 * ChatClient ≈ Microsoft.Extensions.AI 的 IChatClient；SLF4J Logger ≈ ILogger&lt;LlmClient&gt;。
 */
@Component
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);
    private static final long BASE_BACKOFF_MS = 1000;

    /** 可替换的 sleep，便于测试时不真正等待。C# 对照：≈ 注入 TimeProvider / Func&lt;TimeSpan, Task&gt;。 */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final ChatClient chatClient;
    private final int maxRetries;
    private final Sleeper sleeper;

    /**
     * DI 容器使用的构造函数。ChatClient.Builder 由 Spring AI 自动配置提供（读取 spring.ai.openai.*）。
     * LlmProperties 放在第一个参数：Spring 按参数顺序解析依赖，先绑定并校验它，
     * 缺配置时报出我们自己的"缺少环境变量 LLM_xxx"，而不是 Spring AI 内部的报错。
     */
    @Autowired
    public LlmClient(LlmProperties llmProperties, ReviewProperties reviewProperties,
                     ChatClient.Builder chatClientBuilder) {
        this(chatClientBuilder.build(), reviewProperties.maxRetries(), Thread::sleep);
        log.info("llm_client_ready {}", llmProperties);
    }

    /** 测试用构造函数（包可见 ≈ C# internal + InternalsVisibleTo）。 */
    LlmClient(ChatClient chatClient, int maxRetries, Sleeper sleeper) {
        this.chatClient = chatClient;
        this.maxRetries = maxRetries;
        this.sleeper = sleeper;
    }

    /**
     * 发起一次逻辑调用（内部可能重试多次）。
     *
     * @param label 计量标签：文件路径，或 "single"
     * @throws LlmCallException 重试耗尽或遇到不可重试错误
     */
    public LlmResponse call(String label, String systemPrompt, String userPrompt) {
        // 直接构造 Message 而不用 ChatClient 的 .system(String)/.user(String)，
        // 避免 diff 中 Java 代码的 { } 被当作模板占位符解析
        Prompt prompt = new Prompt(List.of(new SystemMessage(systemPrompt), new UserMessage(userPrompt)));

        long start = System.nanoTime(); // ≈ Stopwatch.GetTimestamp()
        int attempts = 0;
        RuntimeException lastError;
        List<String> attemptErrors = new ArrayList<>(); // 每次失败尝试一条，写进报告用于溯源

        while (true) {
            attempts++;
            try {
                ChatResponse response = chatClient.prompt(prompt).call().chatResponse();
                String content = extractContent(response);
                Usage usage = response.getMetadata().getUsage();
                CallMetrics metrics = new CallMetrics(label,
                        tokens(usage == null ? null : usage.getPromptTokens()),
                        tokens(usage == null ? null : usage.getCompletionTokens()),
                        elapsedMs(start), true, attempts);
                if (usage == null || usage.getPromptTokens() == null) {
                    log.warn("llm_usage_missing label={} — API 未返回 usage，token 记为 0", label);
                }
                logCall(metrics, null);
                return new LlmResponse(content, metrics, attemptErrors);
            } catch (RuntimeException e) {
                lastError = e;
                attemptErrors.add("第 " + attempts + " 次尝试失败（" + elapsedMs(start) + "ms）: " + ReviewError.describe(e));
                if (attempts > maxRetries || !isRetryable(e)) {
                    break;
                }
                long backoff = BASE_BACKOFF_MS << (attempts - 1); // 1s, 2s, 4s ...
                log.warn("llm_retry label={} attempt={} backoffMs={} error={}", label, attempts, backoff, e.toString());
                try {
                    sleeper.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt(); // ≈ 响应 CancellationToken
                    break;
                }
            }
        }

        CallMetrics metrics = new CallMetrics(label, 0, 0, elapsedMs(start), false, attempts);
        logCall(metrics, lastError);
        throw new LlmCallException(metrics, lastError, attemptErrors);
    }

    private static String extractContent(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            throw new IllegalStateException("LLM 返回为空（无 choices）");
        }
        String text = response.getResult().getOutput().getText();
        return text == null ? "" : text;
    }

    /**
     * 4xx（鉴权失败、请求非法、超出上下文等）重试无意义，直接失败；但 429 限流要重试。
     * 5xx、超时、网络错误都重试。
     */
    static boolean isRetryable(RuntimeException e) {
        if (e instanceof NonTransientAiException) {
            String msg = e.getMessage();
            return msg != null && msg.contains("429");
        }
        return true;
    }

    private static int tokens(Integer value) {
        return value == null ? 0 : value;
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /** 每次逻辑调用一条结构化日志（key=value，便于 grep）。≈ ILogger 的结构化消息模板。 */
    private static void logCall(CallMetrics m, Throwable error) {
        if (m.success()) {
            log.info("llm_call label={} inputTokens={} outputTokens={} latencyMs={} success=true attempts={}",
                    m.label(), m.inputTokens(), m.outputTokens(), m.latencyMs(), m.attempts());
        } else {
            log.error("llm_call label={} inputTokens={} outputTokens={} latencyMs={} success=false attempts={} error={}",
                    m.label(), m.inputTokens(), m.outputTokens(), m.latencyMs(), m.attempts(),
                    error == null ? "interrupted" : error.toString());
        }
    }
}
