package com.lee.prreviewer.model;

/**
 * 一条审查发现。
 *
 * @param file 文件路径。mapreduce 模式由代码填入，不让 LLM 输出；single 模式由 LLM 填。
 * @param line 新文件（源文件改动后版本）中的行号
 */
public record Finding(
        String file,
        int line,
        Category category,
        Severity severity,
        String message,
        String suggestion
) {}
