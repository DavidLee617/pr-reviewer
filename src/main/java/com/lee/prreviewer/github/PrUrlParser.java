package com.lee.prreviewer.github;

import com.lee.prreviewer.model.ReviewRequest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析 PR 链接：https://github.com/{owner}/{repo}/pull/{number}
 * 允许尾部带 /files、/commits、查询串或锚点。
 * C# 对照：Pattern/Matcher ≈ Regex/Match；final 类 + 私有构造 ≈ C# static class。
 */
public final class PrUrlParser {

    private static final Pattern PR_URL = Pattern.compile(
            "^https?://(?:www\\.)?github\\.com/([^/\\s]+)/([^/\\s]+)/pull/(\\d+)(?:/[^?#\\s]*)?(?:[?#]\\S*)?$");

    private PrUrlParser() {}

    /** @throws IllegalArgumentException 链接格式不对（≈ C# ArgumentException） */
    public static ReviewRequest parse(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("PR 链接为空");
        }
        Matcher m = PR_URL.matcher(url.strip());
        if (!m.matches()) {
            throw new IllegalArgumentException(
                    "无法解析 PR 链接: " + url + "，期望格式 https://github.com/{owner}/{repo}/pull/{number}");
        }
        int number;
        try {
            number = Integer.parseInt(m.group(3)); // ≈ int.Parse
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("PR 编号超出范围: " + m.group(3), e);
        }
        if (number <= 0) {
            throw new IllegalArgumentException("PR 编号必须为正数: " + url);
        }
        return new ReviewRequest(m.group(1), m.group(2), number);
    }
}
