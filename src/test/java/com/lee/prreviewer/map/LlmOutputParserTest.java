package com.lee.prreviewer.map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lee.prreviewer.map.LlmOutputParser.ParsedFinding;
import com.lee.prreviewer.model.Category;
import com.lee.prreviewer.model.Severity;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LlmOutputParserTest {

    private final LlmOutputParser parser = new LlmOutputParser(new ObjectMapper());

    @Test
    void parsesStandardOutput() throws Exception {
        List<ParsedFinding> r = parser.parse("""
                {"findings":[{"line":43,"category":"SECURITY","severity":"HIGH","message":"SQL 拼接","suggestion":"用参数绑定"}]}""");

        assertThat(r).containsExactly(new ParsedFinding("", 43, Category.SECURITY, Severity.HIGH, "SQL 拼接", "用参数绑定"));
    }

    @Test
    void toleratesCodeFenceLowercaseEnumsStringLineAndMissingSuggestion() throws Exception {
        List<ParsedFinding> r = parser.parse("""
                下面是结果：
                ```json
                {"findings":[{"file":"A.java","line":"7","category":"naming","severity":"Low","message":"变量名 tmp 不清晰"}]}
                ```""");

        assertThat(r).containsExactly(new ParsedFinding("A.java", 7, Category.NAMING, Severity.LOW, "变量名 tmp 不清晰", ""));
    }

    @Test
    void acceptsEmptyFindingsAndBareArray() throws Exception {
        assertThat(parser.parse("{\"findings\": []}")).isEmpty();
        assertThat(parser.parse("[]")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "没有发现问题",
            "{\"findings\": [",
            "{\"result\": []}",
            "{\"findings\": [{\"category\":\"STYLE\",\"severity\":\"LOW\",\"message\":\"缺 line\"}]}",
            "{\"findings\": [{\"line\":1.5,\"category\":\"STYLE\",\"severity\":\"LOW\",\"message\":\"小数行号\"}]}",
            "{\"findings\": [{\"line\":1,\"category\":\"BUG\",\"severity\":\"LOW\",\"message\":\"非法类别\"}]}",
            "{\"findings\": [{\"line\":1,\"category\":\"STYLE\",\"severity\":\"CRITICAL\",\"message\":\"非法级别\"}]}",
            "{\"findings\": [{\"line\":1,\"category\":\"STYLE\",\"severity\":\"LOW\",\"message\":\"\"}]}"
    })
    void rejectsInvalidOutput(String output) {
        assertThatThrownBy(() -> parser.parse(output)).isInstanceOf(InvalidLlmOutputException.class);
    }
}
