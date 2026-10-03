package com.lee.prreviewer.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lee.prreviewer.config.ReviewProperties;
import com.lee.prreviewer.github.GitHubApiException;
import com.lee.prreviewer.github.GitHubPrClient;
import com.lee.prreviewer.github.PrFile;
import com.lee.prreviewer.github.PrInfo;
import com.lee.prreviewer.llm.CallMetrics;
import com.lee.prreviewer.map.FileReviewResult;
import com.lee.prreviewer.map.FileReviewer;
import com.lee.prreviewer.model.ReviewError;
import com.lee.prreviewer.model.ReviewMode;
import com.lee.prreviewer.model.ReviewReport;
import com.lee.prreviewer.model.ReviewStage;
import com.lee.prreviewer.preprocess.FileFilter;
import com.lee.prreviewer.preprocess.PatchParser;
import com.lee.prreviewer.preprocess.PrSummaryBuilder;
import com.lee.prreviewer.reduce.FindingAggregator;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 流水线层的错误溯源：预处理失败不抛异常、single 整次失败时 failedFiles 与 errors。 */
class ReviewPipelineTest {

    private static final String URL = "https://github.com/o/r/pull/1";
    private static final String PATCH = "@@ -1,1 +1,2 @@\n a\n+b";

    private final GitHubPrClient gitHub = mock(GitHubPrClient.class);
    private final FileReviewer fileReviewer = mock(FileReviewer.class);
    private final SingleCallReviewer single = mock(SingleCallReviewer.class);
    private final ReviewProperties props = new ReviewProperties(2, 2, 1500,
            new ReviewProperties.Filter(List.of("**/*.java"), List.of()));
    private final ReviewPipeline pipeline = new ReviewPipeline(gitHub, new PatchParser(), new FileFilter(props),
            new PrSummaryBuilder(props), fileReviewer, new MapReduceReviewer(fileReviewer, props), single,
            new FindingAggregator());

    @Test
    void gitHubFailureBecomesPrepareErrorInsteadOfException() {
        when(gitHub.getPullRequest(any())).thenThrow(new GitHubApiException(404,
                "仓库或 PR 不存在 — HTTP 404 GET https://api.github.com/repos/o/r/pulls/1 — GitHub message: Not Found"));

        ReviewReport report = pipeline.review(URL, ReviewMode.MAPREDUCE, ReviewProgressListener.NONE);

        assertThat(report.prUrl()).isEqualTo(URL);
        assertThat(report.findings()).isEmpty();
        assertThat(report.calls()).isEmpty();
        assertThat(report.errors()).singleElement().satisfies(e -> {
            assertThat(e.stage()).isEqualTo(ReviewStage.PREPARE);
            assertThat(e.recovered()).isFalse();
            assertThat(e.message()).contains("GitHubApiException", "HTTP 404", "/repos/o/r/pulls/1");
        });
        verifyNoInteractions(fileReviewer);
    }

    @Test
    void invalidUrlBecomesPrepareError() {
        ReviewReport report = pipeline.review("not a url", ReviewMode.SINGLE, ReviewProgressListener.NONE);

        assertThat(report.errors()).extracting(ReviewError::stage).containsExactly(ReviewStage.PREPARE);
        verifyNoInteractions(gitHub, single);
    }

    @Test
    void singleCallFailureMarksAllFilesFailedAndKeepsErrors() {
        when(gitHub.getPullRequest(any())).thenReturn(new PrInfo("title", "sha"));
        when(gitHub.listFiles(any())).thenReturn(List.of(
                new PrFile("src/A.java", "modified", PATCH, 1, null),
                new PrFile("src/B.java", "modified", PATCH, 1, null)));
        ReviewError overflow = new ReviewError(ReviewStage.LLM_CALL, SingleCallReviewer.LABEL, SingleCallReviewer.LABEL,
                false, "第 1 次尝试失败: HTTP 400 - maximum context length");
        when(single.review(any(), any())).thenReturn(new FileReviewResult(SingleCallReviewer.LABEL, List.of(),
                List.of(new CallMetrics(SingleCallReviewer.LABEL, 0, 0, 500, false, 1)), "LLM 调用失败", List.of(overflow)));

        ReviewReport report = pipeline.review(URL, ReviewMode.SINGLE, ReviewProgressListener.NONE);

        assertThat(report.failedFiles()).containsExactly("src/A.java", "src/B.java");
        assertThat(report.errors()).containsExactly(overflow);
    }

    @Test
    void describeKeepsCauseChain() {
        Exception e = new IllegalStateException("解析 patch 失败: src/A.java",
                new IllegalArgumentException("hunk 头格式错误"));

        assertThat(ReviewError.describe(e))
                .isEqualTo("java.lang.IllegalStateException: 解析 patch 失败: src/A.java"
                        + " ← 原因: java.lang.IllegalArgumentException: hunk 头格式错误");
    }
}
