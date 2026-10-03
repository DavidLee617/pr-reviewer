package com.lee.prreviewer.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lee.prreviewer.model.ReviewMode;
import com.lee.prreviewer.model.ReviewReport;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * eval 能读取 pr-agent 生成的报告：mode=AGENT，且多出一个 agent 对象（ReviewReport 没有这个字段）。
 * eval 用的是 Spring 注入的 ObjectMapper；Spring Boot 的 JacksonAutoConfiguration 用 Jackson2ObjectMapperBuilder
 * 构造它（默认关闭 FAIL_ON_UNKNOWN_PROPERTIES），这里用同一个 builder，不启动整个容器。
 */
class AgentReportCompatibilityTest {

    private final ObjectMapper json = Jackson2ObjectMapperBuilder.json().build();

    @Test
    void readsAgentReportIgnoringAgentObject() throws Exception {
        String report = """
                {
                  "mode": "AGENT",
                  "prUrl": "https://github.com/o/r/pull/1",
                  "headSha": "sha1",
                  "prFiles": ["src/A.java"],
                  "findings": [{"file": "src/A.java", "line": 3, "category": "LOGIC", "severity": "HIGH",
                                "message": "m", "suggestion": "s"}],
                  "failedFiles": [],
                  "skippedFiles": [],
                  "errors": [],
                  "calls": [{"label": "agent#1", "inputTokens": 10, "outputTokens": 2, "latencyMs": 5,
                             "success": true, "attempts": 1}],
                  "totalLatencyMs": 100,
                  "totalInputTokens": 10,
                  "totalOutputTokens": 2,
                  "agent": {"stoppedByStepLimit": false, "rawFindings": [], "steps": [{"index": 1}]}
                }
                """;

        ReviewReport r = json.readValue(report, ReviewReport.class);

        assertThat(r.mode()).isEqualTo(ReviewMode.AGENT);
        assertThat(r.findings()).hasSize(1);
        assertThat(r.prFiles()).containsExactly("src/A.java");
        assertThat(r.calls()).extracting(c -> c.label()).containsExactly("agent#1");
    }
}
