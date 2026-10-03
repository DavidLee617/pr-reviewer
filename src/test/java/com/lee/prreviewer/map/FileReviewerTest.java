package com.lee.prreviewer.map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lee.prreviewer.llm.CallMetrics;
import com.lee.prreviewer.llm.LlmCallException;
import com.lee.prreviewer.llm.LlmClient;
import com.lee.prreviewer.llm.LlmResponse;
import com.lee.prreviewer.model.Category;
import com.lee.prreviewer.model.ChangeType;
import com.lee.prreviewer.model.FileDiff;
import com.lee.prreviewer.model.Finding;
import com.lee.prreviewer.model.PrSummary;
import com.lee.prreviewer.model.ReviewError;
import com.lee.prreviewer.model.ReviewStage;
import com.lee.prreviewer.model.Severity;
import com.lee.prreviewer.preprocess.PatchParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 用假的 LlmClient 测试重试和非法 JSON，不调真实 LLM。
 * C# 对照：Mockito 的 mock / when(...).thenReturn(...) ≈ Moq 的 new Mock&lt;T&gt;() / Setup(...).Returns(...)；
 * verify(...) ≈ Moq 的 Verify(...)。
 */
class FileReviewerTest {

    private static final String PATH = "src/main/java/com/example/demo/service/OrderService.java";

    /**
     * 新文件行号：10 上下文、11 新增、12 新增、13 上下文（与新增行相邻）、14 上下文、15 上下文（不相邻）。
     * 删除行在 11 之前，不占新行号。
     */
    private static final FileDiff DIFF = new PatchParser().parse(PATH, ChangeType.MODIFIED, String.join("\n",
            "@@ -10,6 +10,6 @@",
            "     public Order payOrder(Long id, Long userId) {",
            "-        Order order = repo.getOne(id);",
            "+        Order order = repo.findById(id).get();",
            "+        order.setStatus(OrderStatus.PAID);",
            "         return repo.save(order);",
            "     }",
            " "));
    private static final PrSummary SUMMARY = new PrSummary("Add payment", List.of(PATH + " (MODIFIED)"), List.of());

    private final LlmClient llm = mock(LlmClient.class);
    private final PromptBuilder prompts = new PromptBuilder();
    private final FileReviewer reviewer = new FileReviewer(
            new JsonRetryingCaller(llm, prompts, new LlmOutputParser(new ObjectMapper())), prompts);

    private static LlmResponse response(String content, String label) {
        return new LlmResponse(content, new CallMetrics(label, 100, 20, 50, true, 1));
    }

    @Test
    void returnsFindingsWithFileFilledByCode() {
        when(llm.call(eq(PATH), anyString(), anyString())).thenReturn(response("""
                {"findings":[{"file":"Other.java","line":11,"category":"STYLE","severity":"medium",
                  "message":"Optional.get() 未判断","suggestion":"使用 orElseThrow"}]}""", PATH));

        FileReviewResult r = reviewer.review(DIFF, SUMMARY);

        assertThat(r.failed()).isFalse();
        assertThat(r.findings()).containsExactly(new Finding(PATH, 11, Category.STYLE, Severity.MEDIUM,
                "Optional.get() 未判断", "使用 orElseThrow"));
        assertThat(r.calls()).hasSize(1);
    }

    @Test
    void retriesOnceWithErrorMessageWhenJsonInvalid() {
        when(llm.call(eq(PATH), anyString(), anyString()))
                .thenReturn(response("好的，以下是审查结果：findings 为空", PATH));
        when(llm.call(eq(PATH + JsonRetryingCaller.JSON_RETRY_SUFFIX), anyString(), contains("无法解析")))
                .thenReturn(response("```json\n{\"findings\": []}\n```", PATH + JsonRetryingCaller.JSON_RETRY_SUFFIX));

        FileReviewResult r = reviewer.review(DIFF, SUMMARY);

        assertThat(r.failed()).isFalse();
        assertThat(r.findings()).isEmpty();
        assertThat(r.calls()).extracting(CallMetrics::label)
                .containsExactly(PATH, PATH + JsonRetryingCaller.JSON_RETRY_SUFFIX);
        // 首次解析失败记为已恢复，附带原始输出开头
        assertThat(r.errors()).singleElement().satisfies(e -> {
            assertThat(e.stage()).isEqualTo(ReviewStage.LLM_OUTPUT);
            assertThat(e.file()).isEqualTo(PATH);
            assertThat(e.callLabel()).isEqualTo(PATH);
            assertThat(e.recovered()).isTrue();
            assertThat(e.message()).contains("输出中没有 JSON", "好的，以下是审查结果");
        });
    }

    @Test
    void failsAfterSecondInvalidOutputWithoutThrowing() {
        when(llm.call(anyString(), anyString(), anyString()))
                .thenReturn(response("{\"findings\": [{\"line\": 11, \"category\": \"BUG\", \"severity\": \"HIGH\", \"message\": \"x\"}]}", PATH));

        FileReviewResult r = reviewer.review(DIFF, SUMMARY);

        assertThat(r.failed()).isTrue();
        assertThat(r.error()).contains("两次均无法解析").contains("category");
        assertThat(r.calls()).hasSize(2);
        assertThat(r.errors()).extracting(ReviewError::stage, ReviewError::callLabel, ReviewError::recovered)
                .containsExactly(
                        tuple(ReviewStage.LLM_OUTPUT, PATH, false),
                        tuple(ReviewStage.LLM_OUTPUT, PATH + JsonRetryingCaller.JSON_RETRY_SUFFIX, false));
    }

    @Test
    void retriedApiErrorsAreRecordedInOrder() {
        // 首次调用：前两次尝试失败后成功，但输出非法；JSON 重试：成功
        when(llm.call(eq(PATH), anyString(), anyString())).thenReturn(new LlmResponse("不是 JSON",
                new CallMetrics(PATH, 100, 20, 3000, true, 3), List.of("第 1 次尝试失败: 503", "第 2 次尝试失败: 503")));
        when(llm.call(eq(PATH + JsonRetryingCaller.JSON_RETRY_SUFFIX), anyString(), anyString()))
                .thenReturn(response("{\"findings\": []}", PATH + JsonRetryingCaller.JSON_RETRY_SUFFIX));

        FileReviewResult r = reviewer.review(DIFF, SUMMARY);

        assertThat(r.failed()).isFalse();
        assertThat(r.errors()).extracting(ReviewError::stage, ReviewError::recovered).containsExactly(
                tuple(ReviewStage.LLM_CALL, true),
                tuple(ReviewStage.LLM_CALL, true),
                tuple(ReviewStage.LLM_OUTPUT, true));
    }

    @Test
    void apiFailureIsRecordedAndNotRetriedAsJson() {
        CallMetrics failedMetrics = new CallMetrics(PATH, 0, 0, 3000, false, 3);
        when(llm.call(anyString(), anyString(), anyString()))
                .thenThrow(new LlmCallException(failedMetrics, new RuntimeException("HTTP 503"),
                        List.of("第 1 次尝试失败: HTTP 503", "第 2 次尝试失败: HTTP 503", "第 3 次尝试失败: HTTP 503")));

        FileReviewResult r = reviewer.review(DIFF, SUMMARY);

        assertThat(r.failed()).isTrue();
        assertThat(r.error()).contains("HTTP 503");
        assertThat(r.calls()).containsExactly(failedMetrics);
        assertThat(r.errors()).hasSize(3)
                .allSatisfy(e -> {
                    assertThat(e.stage()).isEqualTo(ReviewStage.LLM_CALL);
                    assertThat(e.recovered()).isFalse();
                });
        verify(llm, never()).call(eq(PATH + JsonRetryingCaller.JSON_RETRY_SUFFIX), anyString(), anyString());
    }

    @Test
    void dropsFindingsOutsideChangedLines() {
        when(llm.call(eq(PATH), anyString(), anyString())).thenReturn(response("""
                {"findings":[
                  {"line":10,"category":"NAMING","severity":"LOW","message":"上下文行，与新增行相邻"},
                  {"line":12,"category":"SECURITY","severity":"HIGH","message":"新增行"},
                  {"line":13,"category":"STYLE","severity":"LOW","message":"上下文行，与新增行相邻"},
                  {"line":14,"category":"STYLE","severity":"LOW","message":"上下文行，不相邻"},
                  {"line":99,"category":"STYLE","severity":"LOW","message":"不在 diff 中"}]}""", PATH));

        FileReviewResult r = reviewer.review(DIFF, SUMMARY);

        assertThat(r.findings()).extracting(Finding::line).containsExactly(10, 12, 13);
        assertThat(r.errors()).hasSize(2).allSatisfy(e -> {
            assertThat(e.stage()).isEqualTo(ReviewStage.FINDING_VALIDATION);
            assertThat(e.file()).isEqualTo(PATH);
            assertThat(e.message()).contains("行号不在改动范围");
            // map 模式 LLM 不填 file，message 里不应出现空的 "file="
            assertThat(e.message()).doesNotContain("file=");
        });
        assertThat(r.errors()).extracting(ReviewError::message)
                .anySatisfy(m -> assertThat(m).contains("line=14"))
                .anySatisfy(m -> assertThat(m).contains("line=99"));
    }

    @Test
    void reviewableLinesSkipRemovedLinesWhenCheckingAdjacency() {
        assertThat(FileReviewer.reviewableLines(DIFF)).containsExactlyInAnyOrder(10, 11, 12, 13);
    }

    @Test
    void promptContainsRulesSummaryAndDiff() {
        PromptBuilder prompts = new PromptBuilder();

        assertThat(prompts.systemPrompt()).contains("Java 代码审查员", "规则：STYLE", "规则：SECURITY", "规则：NAMING",
                "规则：LOGIC", "规则：PERF");
        assertThat(prompts.fileUserPrompt(SUMMARY, DIFF))
                .contains("PR title: Add payment")
                .contains("File: " + PATH)
                .contains("  11 | +         Order order = repo.findById(id).get();");
    }
}
