package com.lee.prreviewer.app;

import com.lee.prreviewer.map.FileReviewResult;
import com.lee.prreviewer.model.ChangeType;
import com.lee.prreviewer.model.FileDiff;
import com.lee.prreviewer.model.Finding;
import com.lee.prreviewer.model.LineType;
import com.lee.prreviewer.model.PreparedPr;
import com.lee.prreviewer.model.ReviewMode;
import com.lee.prreviewer.model.ReviewReport;
import com.lee.prreviewer.model.SkippedFile;
import com.lee.prreviewer.pipeline.ReviewPipeline;
import com.lee.prreviewer.pipeline.ReviewProgressListener;
import com.lee.prreviewer.reduce.FindingAggregator;
import java.util.List;
import java.util.Locale;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * MCP tools（设计文档 7.2）：只把需要代码执行的操作暴露为 tool，不按审查类别拆。
 * 全部调用现有流水线，和 CLI 共用同一套逻辑。返回值由 Spring AI 序列化为 JSON。
 * <p>
 * 异常（链接非法、GitHub 报错、文件不在 PR 中）由 Spring AI 转成 tool 的错误结果返回给客户端，信息保留原文。
 * C# 对照：{@code @Tool} ≈ MCP C# SDK 的 [McpServerTool]，{@code @ToolParam} ≈ [Description] 标在参数上。
 */
@Component
public class McpTools {

    private final ReviewPipeline pipeline;
    private final FindingAggregator aggregator;

    public McpTools(ReviewPipeline pipeline, FindingAggregator aggregator) {
        this.pipeline = pipeline;
        this.aggregator = aggregator;
    }

    @Tool(name = "review_pr", description = """
            对一个 GitHub PR 做完整代码审查（Java 文件），返回去重排序后的 findings、失败 / 跳过的文件、
            过程中的全部问题（errors，可溯源到环节和文件）以及 token 和耗时。
            mapreduce 模式每个文件一次 LLM 调用；single 模式整个 PR 一次调用（对比用的 baseline）。
            大 PR 可能需要几十秒到几分钟。""")
    public ReviewReport reviewPr(
            @ToolParam(description = "PR 链接，如 https://github.com/owner/repo/pull/1") String prUrl,
            @ToolParam(description = "mapreduce（默认）或 single", required = false) String mode) {
        return pipeline.review(prUrl, parseMode(mode), ReviewProgressListener.NONE);
    }

    @Tool(name = "list_pr_files", description = """
            预处理一个 PR：返回将要审查的文件（路径、变更类型、hunk 数、新增行数）、被跳过的文件及原因、
            以及 PR 摘要。不调用 LLM。可配合 review_file 逐个审查。""")
    public PrFiles listPrFiles(
            @ToolParam(description = "PR 链接，如 https://github.com/owner/repo/pull/1") String prUrl) {
        PreparedPr pr = pipeline.prepare(prUrl);
        List<PrFiles.File> files = pr.files().stream()
                .map(f -> new PrFiles.File(f.path(), f.changeType(), f.hunks().size(), addedLines(f)))
                .toList();
        return new PrFiles(pr.prUrl(), pr.headSha(), files, pr.skippedFiles(), pr.summary().render());
    }

    @Tool(name = "review_file", description = """
            审查 PR 中的单个文件（一次 LLM 调用），返回该文件的 findings、LLM 调用计量和过程中的问题（errors）。
            filePath 用 list_pr_files 返回的完整路径，也接受唯一匹配的路径后缀（如 service/OrderService.java）。""")
    public FileReviewResult reviewFile(
            @ToolParam(description = "PR 链接") String prUrl,
            @ToolParam(description = "文件路径或唯一的路径后缀") String filePath) {
        return pipeline.reviewFile(prUrl, filePath);
    }

    @Tool(name = "aggregate_findings", description = """
            合并多个文件的 findings：按 file + line + category 去重（保留 severity 最高的一条），
            再按 severity（HIGH → LOW）→ file → line 排序。纯代码，不调用 LLM。""")
    public List<Finding> aggregateFindings(
            @ToolParam(description = "review_file 返回的 findings 合并后的列表") List<Finding> findings) {
        return aggregator.aggregate(findings);
    }

    /** list_pr_files 的返回值：不含 diff 正文，避免把整个 PR 塞进 Agent 的上下文。 */
    public record PrFiles(String prUrl, String headSha, List<File> files, List<SkippedFile> skippedFiles,
                          String summary) {
        public record File(String path, ChangeType changeType, int hunks, long addedLines) {}
    }

    static ReviewMode parseMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return ReviewMode.MAPREDUCE;
        }
        try {
            return ReviewMode.valueOf(mode.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("mode 只能是 mapreduce 或 single，收到: " + mode, e);
        }
    }

    private static long addedLines(FileDiff f) {
        return f.hunks().stream().flatMap(h -> h.lines().stream()).filter(l -> l.type() == LineType.ADDED).count();
    }
}
