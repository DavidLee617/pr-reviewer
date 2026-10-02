package com.lee.prreviewer.model;

/**
 * diff 中的一行（内容是被审查的 Java 源码）。
 *
 * @param newLineNo 新文件中的行号；REMOVED 行在新文件中不存在，为 null。
 *                  C# 对照：Integer（装箱类型，可为 null）≈ int?；int 不能为 null ≈ int。
 */
public record DiffLine(LineType type, Integer newLineNo, String content) {}
