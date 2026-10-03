package com.lee.prreviewer.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;

/**
 * 用假的 ChatModel 测试 LlmClient，不调真实 LLM。
 * C# 对照：@Test ≈ xUnit 的 [Fact]；assertThat(...).isEqualTo(...) ≈ FluentAssertions 的 .Should().Be(...)。
 */
class LlmClientTest {

    /** 按顺序返回预设结果的假模型。≈ 手写的 Moq SetupSequence。 */
    static class FakeChatModel implements ChatModel {
        final Deque<Supplier<ChatResponse>> script = new ArrayDeque<>();
        final List<Prompt> received = new ArrayList<>();

        @Override
        public ChatResponse call(Prompt prompt) {
            received.add(prompt);
            return script.removeFirst().get();
        }
    }

    static ChatResponse reply(String text, int in, int out) {
        return ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage(text))))
                .metadata(ChatResponseMetadata.builder().usage(new DefaultUsage(in, out)).build())
                .build();
    }

    private final FakeChatModel model = new FakeChatModel();
    private final List<Long> sleeps = new ArrayList<>();
    private final LlmClient client = new LlmClient(ChatClient.create(model), 2, sleeps::add);

    @Test
    void successRecordsUsageFromApi() {
        model.script.add(() -> reply("pong", 12, 3));

        LlmResponse r = client.call("ping", "sys", "user");

        assertThat(r.content()).isEqualTo("pong");
        assertThat(r.metrics().label()).isEqualTo("ping");
        assertThat(r.metrics().inputTokens()).isEqualTo(12);
        assertThat(r.metrics().outputTokens()).isEqualTo(3);
        assertThat(r.metrics().success()).isTrue();
        assertThat(r.metrics().attempts()).isEqualTo(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void curlyBracesInJavaDiffAreNotTreatedAsTemplate() {
        model.script.add(() -> reply("{\"findings\":[]}", 1, 1));
        String javaCode = "  42 | + public Book getById(Long id) { return repo.findById(id).orElseThrow(); }";

        client.call("BookService.java", "sys", javaCode);

        assertThat(model.received.get(0).getUserMessage().getText()).isEqualTo(javaCode);
    }

    @Test
    void transientErrorIsRetriedWithExponentialBackoff() {
        model.script.add(() -> { throw new TransientAiException("HTTP 503"); });
        model.script.add(() -> { throw new TransientAiException("HTTP 503"); });
        model.script.add(() -> reply("ok", 5, 1));

        LlmResponse r = client.call("A.java", "sys", "user");

        assertThat(r.metrics().attempts()).isEqualTo(3);
        assertThat(sleeps).containsExactly(1000L, 2000L);
        // 被重试恢复的失败也要留下记录，用于溯源
        assertThat(r.attemptErrors()).hasSize(2)
                .allSatisfy(msg -> assertThat(msg).contains("TransientAiException", "HTTP 503"));
        assertThat(r.attemptErrors().get(0)).startsWith("第 1 次尝试失败");
    }

    @Test
    void failsAfterMaxRetriesKeepingOriginalCause() {
        for (int i = 0; i < 3; i++) {
            model.script.add(() -> { throw new TransientAiException("HTTP 502"); });
        }

        assertThatThrownBy(() -> client.call("A.java", "sys", "user"))
                .isInstanceOf(LlmCallException.class)
                .hasRootCauseMessage("HTTP 502")
                .satisfies(e -> {
                    CallMetrics m = ((LlmCallException) e).metrics();
                    assertThat(m.success()).isFalse();
                    assertThat(m.attempts()).isEqualTo(3);
                    assertThat(((LlmCallException) e).attemptErrors()).hasSize(3);
                });
    }

    @Test
    void nonTransientErrorIsNotRetried() {
        model.script.add(() -> { throw new NonTransientAiException("HTTP 401 - invalid api key"); });

        assertThatThrownBy(() -> client.call("A.java", "sys", "user"))
                .isInstanceOf(LlmCallException.class)
                .satisfies(e -> assertThat(((LlmCallException) e).metrics().attempts()).isEqualTo(1));
        assertThat(sleeps).isEmpty();
    }

    @Test
    void rateLimitIsRetried() {
        model.script.add(() -> { throw new NonTransientAiException("HTTP 429 - rate limit"); });
        model.script.add(() -> reply("ok", 1, 1));

        assertThat(client.call("A.java", "sys", "user").metrics().attempts()).isEqualTo(2);
    }
}
