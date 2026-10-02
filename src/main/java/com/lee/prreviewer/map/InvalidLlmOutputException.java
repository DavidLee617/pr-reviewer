package com.lee.prreviewer.map;

/** LLM 输出不是合法 JSON，或结构不符合约定的 findings 格式。 */
public class InvalidLlmOutputException extends Exception {
    // 继承 Exception（受检异常）：调用方必须显式处理，≈ 编译器强制你写 try/catch。C# 没有受检异常的概念。

    public InvalidLlmOutputException(String message) {
        super(message);
    }

    public InvalidLlmOutputException(String message, Throwable cause) {
        super(message, cause);
    }
}
