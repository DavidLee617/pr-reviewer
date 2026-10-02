package com.lee.prreviewer.preprocess;

import com.lee.prreviewer.config.ReviewProperties;
import com.lee.prreviewer.github.PrFile;
import com.lee.prreviewer.model.ChangeType;
import com.lee.prreviewer.model.SkippedFile;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

/**
 * 决定一个文件是否跳过审查，按顺序检查：
 * 1. status = removed
 * 2. 匹配 review.filter.exclude-globs（Maven/Gradle 构建产物 target/、build/，自动生成代码 generated/）
 * 3. 不匹配 review.filter.include-globs（默认只审查 *.java，配置、文档等其他文件跳过）
 * 4. 没有 patch（二进制文件或改动过大，GitHub 不返回），或仅重命名无内容改动
 * <p>
 * glob 用 AntPathMatcher：开头的双星斜杠可匹配零层目录，所以 target 规则也能匹配仓库根下的 target/。
 * C# 对照：Optional&lt;T&gt; ≈ 可空返回值 SkippedFile?（Java 引用类型没有 ? 可空标注）。
 */
@Component
public class FileFilter {

    private final List<String> includeGlobs;
    private final List<String> excludeGlobs;
    private final AntPathMatcher matcher = new AntPathMatcher();

    @Autowired
    public FileFilter(ReviewProperties props) {
        this(props.filter().includeGlobs(), props.filter().excludeGlobs());
    }

    FileFilter(List<String> includeGlobs, List<String> excludeGlobs) {
        this.includeGlobs = List.copyOf(includeGlobs);
        this.excludeGlobs = List.copyOf(excludeGlobs);
    }

    /** @return 需要跳过时返回原因；可以审查时返回 Optional.empty() */
    public Optional<SkippedFile> check(PrFile file) {
        String path = file.filename();
        if (ChangeType.fromGitHubStatus(file.status()) == ChangeType.REMOVED) {
            return skip(path, "文件已删除");
        }
        for (String glob : excludeGlobs) {
            if (matcher.match(glob, path)) {
                return skip(path, "匹配排除规则 " + glob);
            }
        }
        // ≈ LINQ: includeGlobs.Any(g => matcher.Match(g, path))
        if (includeGlobs.stream().noneMatch(glob -> matcher.match(glob, path))) {
            return skip(path, "不在审查范围（include 规则 " + includeGlobs + "）");
        }
        if (file.patch() == null || file.patch().isBlank()) {
            if (ChangeType.fromGitHubStatus(file.status()) == ChangeType.RENAMED && file.changes() == 0) {
                return skip(path, "仅重命名，无内容改动");
            }
            return skip(path, "无 patch（二进制文件或改动过大，GitHub 未返回）");
        }
        return Optional.empty();
    }

    private static Optional<SkippedFile> skip(String path, String reason) {
        return Optional.of(new SkippedFile(path, reason));
    }
}
