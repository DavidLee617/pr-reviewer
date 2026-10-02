package com.lee.prreviewer.model;

/** 审查模式。C# 对照：Java enum ≈ C# enum，但 Java 的 enum 是类，可以带字段和方法。 */
public enum ReviewMode {
    /** 每个文件一次 LLM 调用（主模式）。 */
    MAPREDUCE,
    /** 整个 PR 一次 LLM 调用（baseline）。 */
    SINGLE
}
