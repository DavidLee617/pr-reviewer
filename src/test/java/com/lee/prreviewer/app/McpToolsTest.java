package com.lee.prreviewer.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.lee.prreviewer.model.Category;
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
    void registersFourToolsForMcpOnly() {
        List<SyncToolSpecification> specs = new McpServerConfig().prReviewerTools(tools);

        assertThat(specs).extracting(s -> s.tool().name())
                .containsExactlyInAnyOrder("review_pr", "list_pr_files", "review_file", "aggregate_findings");
    }
}
