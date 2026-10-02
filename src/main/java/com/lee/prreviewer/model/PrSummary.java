package com.lee.prreviewer.model;

import java.util.List;

/**
 * PR 摘要，作为每次 map 调用的全局上下文。
 * 长度上限由 PrSummaryBuilder 控制：超限时它截断列表并在列表末尾写入"已截断"说明，
 * 所以 render() 只负责拼接。
 *
 * @param changedFiles            路径 + 变更类型
 * @param changedPublicSignatures 增/删的 Java public 方法、类、接口签名（启发式提取，见 PrSummaryBuilder）
 */
public record PrSummary(
        String title,
        List<String> changedFiles,
        List<String> changedPublicSignatures
) {
    public PrSummary {
        changedFiles = List.copyOf(changedFiles);
        changedPublicSignatures = List.copyOf(changedPublicSignatures);
    }

    /** 渲染成简短文本。C# 对照：StringBuilder 与 System.Text.StringBuilder 用法几乎一致。 */
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("PR title: ").append(title).append('\n');
        sb.append("Changed files:\n");
        changedFiles.forEach(f -> sb.append("- ").append(f).append('\n'));
        if (!changedPublicSignatures.isEmpty()) {
            sb.append("Public signature changes (+ added, - removed):\n");
            changedPublicSignatures.forEach(s -> sb.append(s).append('\n'));
        }
        return sb.toString();
    }
}
