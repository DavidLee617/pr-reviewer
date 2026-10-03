package com.lee.prreviewer.model;

/** 审查模式。C# 对照：Java enum ≈ C# enum，但 Java 的 enum 是类，可以带字段和方法。 */
public enum ReviewMode {
    /** 每个文件一次 LLM 调用（主模式）。 */
    MAPREDUCE,
    /** 整个 PR 一次 LLM 调用（baseline）。 */
    SINGLE,
    /**
     * 由姊妹工程 pr-agent 生成的报告（DeepSeek Agent 自主编排 list_pr_files / review_file / aggregate_findings）。
     * 只用于 eval 读取报告；本工程的 review 命令和 review_pr 不能以此模式运行。
     */
    AGENT;

    /** 本工程的审查流水线能否以此模式运行（AGENT 报告由 pr-agent 生成）。 */
    public boolean runnableByPipeline() {
        return this != AGENT;
    }
}
