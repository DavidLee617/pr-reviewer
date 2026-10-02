package com.lee.prreviewer.model;

/** 未审查的文件及原因，例如：无 patch（过大或二进制）、被过滤规则排除。 */
public record SkippedFile(String path, String reason) {}
