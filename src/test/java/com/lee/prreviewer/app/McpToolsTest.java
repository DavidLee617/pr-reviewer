package com.lee.prreviewer.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lee.prreviewer.model.Category;
import com.lee.prreviewer.model.ChangeType;
import com.lee.prreviewer.model.FileDiff;
import com.lee.prreviewer.model.PrSummary;
import com.lee.prreviewer.model.PreparedPr;
import com.lee.prreviewer.model.ReviewRequest;
import com.lee.prreviewer.model.SkippedFile;
import com.lee.prreviewer.model.Finding;
import com.lee.prreviewer.model.ReviewMode;
import com.lee.prreviewer.model.Severity;
import com.lee.prreviewer.pipeline.ReviewPipeline;
import com.lee.prreviewer.reduce.FindingAggregator;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import java.util.List;
import org.junit.jupiter.api.Test;

/** MCP tools 的参数处理和注册；协议层（stdio、JSON-RPC）由 Spring AI 负责，用真实客户端手工验收。 */
class McpToolsTest {

    private static final String URL = "https://github.com/o/r/pull/1";

    private final ReviewPipeline pipeline = mock(ReviewPipeline.class);
    private final McpTools tools = new McpTools(pipeline, new FindingAggregator());

    @Test
    void modeDefaultsToMapreduceAndIsCaseInsensitive() {
        assertThat(McpTools.parseMode(null)).isEqualTo(ReviewMode.MAPREDUCE);
        assertThat(McpTools.parseMode(" ")).isEqualTo(ReviewMode.MAPREDUCE);
        assertThat(McpTools.parseMode("Single")).isEqualTo(ReviewMode.SINGLE);
        assertThatThrownBy(() -> McpTools.parseMode("fast")).hasMessageContaining("mapreduce 或 single");
        // AGENT 只用于 eval 读取 pr-agent 的报告，不能作为 review_pr 的模式
        assertThatThrownBy(() -> McpTools.parseMode("agent")).hasMessageContaining("mapreduce 或 single");
    }

    @Test
    void reviewPrPassesModeToPipeline() {
        tools.reviewPr(URL, "single");

        verify(pipeline).review(eq(URL), eq(ReviewMode.SINGLE), any());
    }

    @Test
    void aggregateFindingsDeduplicatesAndSorts() {
        Finding low = new Finding("A.java", 10, Category.SECURITY, Severity.LOW, "low", "");
        Finding high = new Finding("A.java", 10, Category.SECURITY, Severity.HIGH, "high", "");
        Finding medium = new Finding("A.java", 3, Category.STYLE, Severity.MEDIUM, "medium", "");

        assertThat(tools.aggregateFindings(List.of(low, medium, high))).containsExactly(high, medium);
    }

    @Test
    void listPrFilesReturnsAllPrFilesInGitHubOrder() {
        FileDiff diff = new FileDiff("src/A.java", ChangeType.MODIFIED, List.of(), "");
        PreparedPr pr = new PreparedPr(new ReviewRequest("o", "r", 1), URL, "sha1",
                List.of("src/A.java", "application.yml"), List.of(diff),
                List.of(new SkippedFile("application.yml", "不在审查范围")),
                new PrSummary("t", List.of(), List.of()));
        when(pipeline.prepare(URL)).thenReturn(pr);

        McpTools.PrFiles result = tools.listPrFiles(URL);

        // prFiles 含被跳过的文件，供 pr-agent 生成报告（eval 按它分段）
        assertThat(result.prFiles()).containsExactly("src/A.java", "application.yml");
        assertThat(result.files()).extracting(McpTools.PrFiles.File::path).containsExactly("src/A.java");
    }

    @Test
    void registersFourToolsForMcpOnly() {
        List<SyncToolSpecification> specs = new McpServerConfig().prReviewerTools(tools);

        assertThat(specs).extracting(s -> s.tool().name())
                .containsExactlyInAnyOrder("review_pr", "list_pr_files", "review_file", "aggregate_findings");
    }
}
