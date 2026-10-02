package com.lee.prreviewer.model;

/**
 * 一次审查请求，由 PrUrlParser 从 PR 链接解析得到。
 * C# 对照：Java record ≈ C# 的 positional record（public record ReviewRequest(string Owner, ...)），
 * 自动生成构造函数、equals/hashCode、toString，字段不可变。
 * 区别：Java 访问器是 owner() 而不是属性 Owner。
 */
public record ReviewRequest(String owner, String repo, int prNumber) {}
