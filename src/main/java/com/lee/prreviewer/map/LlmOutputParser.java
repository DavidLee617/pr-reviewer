package com.lee.prreviewer.map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lee.prreviewer.model.Category;
import com.lee.prreviewer.model.Severity;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * 解析 LLM 输出的 {"findings": [...]}。
 * <p>
 * 容错：去掉 ```json 代码块包裹、前后多余文字；category / severity 不区分大小写。
 * 结构不符（缺 findings 数组、缺 line / message、枚举值非法）一律抛 InvalidLlmOutputException，由调用方决定是否重试。
 * C# 对照：JsonNode ≈ System.Text.Json.Nodes.JsonNode / JsonDocument 的动态访问方式。
 */
@Component
public class LlmOutputParser {

    /** 解析出的单条 finding。file 只有 single 模式由 LLM 填写，map 模式忽略。 */
    public record ParsedFinding(String file, int line, Category category, Severity severity,
                                String message, String suggestion) {}

    private final ObjectMapper json;

    public LlmOutputParser(ObjectMapper json) {
        this.json = json;
    }

    public List<ParsedFinding> parse(String output) throws InvalidLlmOutputException {
        JsonNode root = readJson(extractJson(output));
        JsonNode array = root.isArray() ? root : root.get("findings");
        if (array == null || !array.isArray()) {
            throw new InvalidLlmOutputException("缺少 findings 数组");
        }
        List<ParsedFinding> result = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            result.add(parseOne(array.get(i), i));
        }
        return result;
    }

    private ParsedFinding parseOne(JsonNode n, int index) throws InvalidLlmOutputException {
        String where = "findings[" + index + "]";
        if (n == null || !n.isObject()) {
            throw new InvalidLlmOutputException(where + " 不是对象");
        }
        JsonNode line = n.get("line");
        int lineNo;
        if (line != null && line.isIntegralNumber() && line.canConvertToInt()) {
            lineNo = line.asInt();
        } else if (line != null && line.isTextual()) {
            lineNo = parseIntText(line.asText(), where); // 容错："43"
        } else {
            throw new InvalidLlmOutputException(where + ".line 缺失或不是整数");
        }
        String message = text(n, "message");
        if (message.isBlank()) {
            throw new InvalidLlmOutputException(where + ".message 为空");
        }
        return new ParsedFinding(
                text(n, "file"),
                lineNo,
                enumValue(Category.class, text(n, "category"), where + ".category"),
                enumValue(Severity.class, text(n, "severity"), where + ".severity"),
                message,
                text(n, "suggestion"));
    }

    /** 去掉 markdown 代码块和前后说明文字，取第一个 { 或 [ 到最后一个 } 或 ] 之间的内容。 */
    static String extractJson(String output) throws InvalidLlmOutputException {
        if (output == null || output.isBlank()) {
            throw new InvalidLlmOutputException("输出为空");
        }
        int objStart = output.indexOf('{');
        int arrStart = output.indexOf('[');
        int start = objStart < 0 ? arrStart : (arrStart < 0 ? objStart : Math.min(objStart, arrStart));
        int end = Math.max(output.lastIndexOf('}'), output.lastIndexOf(']'));
        if (start < 0 || end < start) {
            throw new InvalidLlmOutputException("输出中没有 JSON");
        }
        return output.substring(start, end + 1);
    }

    private JsonNode readJson(String text) throws InvalidLlmOutputException {
        try {
            return json.readTree(text);
        } catch (JsonProcessingException e) {
            throw new InvalidLlmOutputException("不是合法 JSON: " + e.getOriginalMessage(), e);
        }
    }

    private static int parseIntText(String s, String where) throws InvalidLlmOutputException {
        try {
            return Integer.parseInt(s.strip());
        } catch (NumberFormatException e) {
            throw new InvalidLlmOutputException(where + ".line 不是整数: " + s, e);
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? "" : v.asText();
    }

    /** C# 对照：泛型方法 + Class&lt;E&gt; 参数 ≈ Enum.TryParse&lt;TEnum&gt;(value, ignoreCase: true, out var e)。 */
    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String where)
            throws InvalidLlmOutputException {
        try {
            return Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidLlmOutputException(where + " 取值非法: \"" + value + "\"", e);
        }
    }
}
