package com.lee.prreviewer.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lee.prreviewer.llm.CallMetrics;
import com.lee.prreviewer.llm.LlmCallException;
import com.lee.prreviewer.llm.LlmClient;
import com.lee.prreviewer.llm.LlmResponse;
import com.lee.prreviewer.map.FileReviewResult;
import com.lee.prreviewer.map.JsonRetryingCaller;
import com.lee.prreviewer.map.LlmOutputParser;
import com.lee.prreviewer.map.PromptBuilder;
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
import org.mockito.ArgumentCaptor;

/** 用假的 LlmClient 测试 single 模式的 file 校验、行号校验和失败处理，不调真实 LLM。 */
class SingleCallReviewerTest {

    private static final String ORDER = "src/main/java/com/example/demo/service/OrderService.java";
    private static final String BOOK = "src/main/java/com/example/demo/service/BookService.java";

    /** 两个文件，新增行都是 11、12（相邻上下文 10、13）。 */
    private static FileDiff diff(String path) {
        return new PatchParser().parse(path, ChangeType.MODIFIED, String.join("\n",
                "@@ -10,4 +10,5 @@",
                "     void a() {",
                "-        old();",
                "+        first();",
                "+        second();",
                "     }",
                " ",
                " "));
    }

    private static final List<FileDiff> FILES = List.of(diff(ORDER), diff(BOOK));
    private static final PrSummary SUMMARY = new PrSummary("t", List.of(ORDER, BOOK), List.of());

    private final LlmClient llm = mock(LlmClient.class);
    private final PromptBuilder prompts = new PromptBuilder();
    private final SingleCallReviewer reviewer = new SingleCallReviewer(
            new JsonRetryingCaller(llm, prompts, new LlmOutputParser(new ObjectMapper())), prompts);

    private static LlmResponse response(String content) {
        return new LlmResponse(content, new CallMetrics(SingleCallReviewer.LABEL, 5000, 300, 9000, true, 1));
    }

    @Test
    void keepsFindingsWithValidFileAndLine() {
        when(llm.call(eq(SingleCallReviewer.LABEL), anyString(), anyString())).thenReturn(response("""
                {"findings":[
                  {"file":"%s","line":11,"category":"SECURITY","severity":"HIGH","message":"完整路径"},
                  {"file":"service/BookService.java","line":12,"category":"STYLE","severity":"LOW","message":"后缀"},
                  {"file":"Unknown.java","line":11,"category":"STYLE","severity":"LOW","message":"不在 PR 中"},
                  {"line":11,"category":"STYLE","severity":"LOW","message":"缺 file"},
                  {"file":"%s","line":15,"category":"STYLE","severity":"LOW","message":"行号越界"}]}
                """.formatted(ORDER, ORDER)));

        FileReviewResult r = reviewer.review(FILES, SUMMARY);

        assertThat(r.failed()).isFalse();
        assertThat(r.file()).isEqualTo(SingleCallReviewer.LABEL);
        assertThat(r.findings()).containsExactly(
                new Finding(ORDER, 11, Category.SECURITY, Severity.HIGH, "完整路径", ""),
                new Finding(BOOK, 12, Category.STYLE, Severity.LOW, "后缀", ""));
        // 3 条被丢弃的 finding 都要能溯源到原因
        assertThat(r.errors()).extracting(ReviewError::stage).containsOnly(ReviewStage.FINDING_VALIDATION);
        assertThat(r.errors()).extracting(ReviewError::file, ReviewError::callLabel)
                .containsExactly(
                        tuple(SingleCallReviewer.LABEL, SingleCallReviewer.LABEL),
                        tuple(SingleCallReviewer.LABEL, SingleCallReviewer.LABEL),
                        tuple(ORDER, SingleCallReviewer.LABEL));
        assertThat(r.errors()).extracting(ReviewError::message).satisfiesExactly(
                m -> assertThat(m).contains("file 不是本次审查的文件", "file=Unknown.java"),
                m -> assertThat(m).contains("file 不是本次审查的文件", "缺 file"),
                m -> assertThat(m).contains("行号不在改动范围", "line=15"));
    }

    @Test
    void ambiguousSuffixIsDropped() {
        assertThat(SingleCallReviewer.resolve("Service.java", FILES)).isEmpty();
        assertThat(SingleCallReviewer.resolve("OrderService.java", FILES)).map(FileDiff::path).hasValue(ORDER);
    }

    @Test
    void promptContainsAllDiffsAndFileRequirement() {
        when(llm.call(anyString(), anyString(), anyString())).thenReturn(response("{\"findings\": []}"));

        reviewer.review(FILES, SUMMARY);

        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        verify(llm).call(eq(SingleCallReviewer.LABEL), eq(prompts.systemPrompt()), user.capture());
        assertThat(user.getValue())
                .contains("File: " + ORDER, "File: " + BOOK, "共 2 个文件", "\"file\" 字段");
    }

    @Test
    void contextOverflowFailsWithoutJsonRetry() {
        CallMetrics failed = new CallMetrics(SingleCallReviewer.LABEL, 0, 0, 800, false, 1);
        when(llm.call(anyString(), anyString(), anyString())).thenThrow(new LlmCallException(failed,
                new RuntimeException("HTTP 400 - This model's maximum context length is 131072 tokens")));

        FileReviewResult r = reviewer.review(FILES, SUMMARY);

        assertThat(r.failed()).isTrue();
        assertThat(r.error()).contains("maximum context length");
        assertThat(r.calls()).containsExactly(failed);
        verify(llm, never()).call(eq(SingleCallReviewer.LABEL + JsonRetryingCaller.JSON_RETRY_SUFFIX),
                anyString(), anyString());
    }

    @Test
    void noFilesMeansNoCall() {
        FileReviewResult r = reviewer.review(List.of(), SUMMARY);

        assertThat(r.failed()).isFalse();
        assertThat(r.calls()).isEmpty();
        verifyNoInteractions(llm);
    }
}
