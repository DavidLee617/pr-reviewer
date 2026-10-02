package com.lee.prreviewer.github;

/** PR 基本信息：标题与 head commit sha。 */
public record PrInfo(String title, String headSha) {}
