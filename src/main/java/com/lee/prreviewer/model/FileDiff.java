package com.lee.prreviewer.model;

import java.util.List;

/**
 * 单个文件的解析后 diff。
 *
 * @param path          GitHub 返回的 filename，例如 src/main/java/com/example/demo/service/BookService.java
 * @param annotatedDiff 带新文件行号的 diff 文本，直接喂给 LLM（设计文档 6.2）
 */
public record FileDiff(
        String path,
        ChangeType changeType,
        List<Hunk> hunks,
        String annotatedDiff
) {
    public FileDiff {
        hunks = List.copyOf(hunks);
    }
}
