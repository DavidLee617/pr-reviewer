package com.lee.prreviewer.model;

/** 出问题的环节，用于 ReviewError 溯源。顺序即流水线顺序。 */
public enum ReviewStage {
    /** 解析 PR 链接、拉取 GitHub、解析 patch。失败时整个审查无法进行。 */
    PREPARE,
    /** 某一次 LLM API 调用尝试失败（超时、5xx、429、4xx 如超出上下文）。 */
    LLM_CALL,
    /** LLM 输出无法解析为 findings JSON，或结构不符。 */
    LLM_OUTPUT,
    /** finding 被丢弃：file 不是本次审查的文件，或行号不在改动范围。 */
    FINDING_VALIDATION,
    /** 未预期的异常（通常是代码 bug）。 */
    INTERNAL
}
