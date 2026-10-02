package com.lee.prreviewer.map;

import com.lee.prreviewer.model.FileDiff;
import com.lee.prreviewer.model.PrSummary;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 组装 prompt：系统提示 + 三套规则 + PR 摘要 + diff。
 * <p>
 * 系统提示和规则放在 system 消息里，PR 摘要放在 user 消息开头：同一个 PR 的所有 map 调用，
 * 前缀（system + 摘要）完全相同，支持前缀缓存的接口（如 DeepSeek、OpenAI）会自动命中缓存，降低成本。
 * <p>
 * C# 对照：ClassPathResource ≈ 读取嵌入资源（Assembly.GetManifestResourceStream），
 * 这些 .md 文件打包在 jar 内。
 */
@Component
public class PromptBuilder {

    private static final int PREVIOUS_OUTPUT_PREVIEW_CHARS = 500;

    private final String systemPrompt;

    public PromptBuilder() {
        this.systemPrompt = load("prompts/system.md")
                + "\n# 规则：STYLE\n" + load("rules/style.md")
                + "\n# 规则：SECURITY\n" + load("rules/security.md")
                + "\n# 规则：NAMING\n" + load("rules/naming.md");
    }

    public String systemPrompt() {
        return systemPrompt;
    }

    /** 单文件 map 调用的 user 消息。 */
    public String fileUserPrompt(PrSummary summary, FileDiff file) {
        return "## PR 摘要（仅供理解上下文）\n" + summary.render()
                + "\n## 待审查的 diff\n" + file.annotatedDiff();
    }

    /** 输出无法解析时的重试消息：原请求 + 错误信息 + 上次输出的开头。 */
    public String jsonRetryPrompt(String originalUserPrompt, String previousOutput, String error) {
        String preview = previousOutput == null ? "" : previousOutput;
        if (preview.length() > PREVIOUS_OUTPUT_PREVIEW_CHARS) {
            preview = preview.substring(0, PREVIOUS_OUTPUT_PREVIEW_CHARS) + "…";
        }
        return originalUserPrompt
                + "\n\n## 注意\n你上一次的输出无法解析：" + error
                + "\n上一次输出的开头：\n" + preview
                + "\n\n请重新审查，并严格只输出 {\"findings\": [...]} 格式的 JSON 对象，不要 markdown 代码块或其他文字。";
    }

    private static String load(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) { // try-with-resources ≈ C# using
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("无法读取 prompt 资源: " + path, e);
        }
    }
}
