package com.lee.prreviewer.model;

/**
 * 审查过程中出现的一个问题，保证任何一步出错都能在报告里溯源到环节、文件和具体调用。
 *
 * @param stage     出问题的环节
 * @param file      涉及的文件；single 模式整次调用的问题为 "single"；PREPARE 阶段为 null
 * @param callLabel 相关 LLM 调用的 label（与 CallMetrics.label 和日志一致，含 " [json-retry]" 后缀）；非 LLM 环节为 null
 * @param recovered true = 已通过重试恢复，不影响结果；false = 导致文件审查失败或 finding 被丢弃
 * @param message   原始错误信息（含异常类型和 cause 链）
 */
public record ReviewError(ReviewStage stage, String file, String callLabel, boolean recovered, String message) {

    private static final int MAX_CAUSE_DEPTH = 10;

    /** 异常 + 完整 cause 链，不丢失原始原因。≈ C# 的 ex.ToString() 去掉堆栈。 */
    public static String describe(Throwable e) {
        StringBuilder sb = new StringBuilder(String.valueOf(e));
        Throwable c = e.getCause();
        for (int depth = 0; c != null && depth < MAX_CAUSE_DEPTH; depth++, c = c.getCause()) {
            sb.append(" ← 原因: ").append(c);
            if (c.getCause() == c) {
                break;
            }
        }
        return sb.toString();
    }
}
