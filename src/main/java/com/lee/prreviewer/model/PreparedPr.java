package com.lee.prreviewer.model;

import java.util.List;

/**
 * 预处理完成的 PR：可审查的文件 + 跳过的文件 + PR 摘要。
 * 是 map 阶段的输入，也是 MCP tool list_pr_files 的输出（M7）。
 *
 * @param headSha PR head commit，M8 写回评论时需要
 * @param prFiles PR 的全部文件路径（含被跳过的），保持 GitHub 返回的顺序；评估按它分前 / 中 / 后三段
 */
public record PreparedPr(
        ReviewRequest request,
        String prUrl,
        String headSha,
        List<String> prFiles,
        List<FileDiff> files,
        List<SkippedFile> skippedFiles,
        PrSummary summary
) {
    public PreparedPr {
        prFiles = List.copyOf(prFiles);
        files = List.copyOf(files);
        skippedFiles = List.copyOf(skippedFiles);
    }
}
