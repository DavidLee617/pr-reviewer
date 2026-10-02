package com.lee.prreviewer.model;

/** 文件变更类型，对应 GitHub files API 的 status 字段。 */
public enum ChangeType {
    ADDED, MODIFIED, REMOVED, RENAMED;

    /**
     * GitHub status 取值：added / removed / modified / renamed / copied / changed / unchanged。
     * C# 对照：Java 14+ 的 switch 表达式 ≈ C# 8 的 switch expression。
     */
    public static ChangeType fromGitHubStatus(String status) {
        return switch (status == null ? "" : status) {
            case "added", "copied" -> ADDED;
            case "removed" -> REMOVED;
            case "renamed" -> RENAMED;
            default -> MODIFIED; // modified / changed / unchanged
        };
    }
}
