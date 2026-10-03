package com.lee.prreviewer.app;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import java.util.List;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP server 入口（设计文档 7.2）：把 {@link McpTools} 里的 @Tool 方法注册为 MCP tools。
 * <p>
 * server 本身（stdio 传输、协议处理）由 spring-ai-starter-mcp-server 自动配置，
 * 只在 `mcp` 命令下启用（spring.ai.mcp.server.enabled，见 PrReviewerApplication）。
 * <p>
 * 注册成 MCP 专用的 SyncToolSpecification，而不是 ToolCallbackProvider bean：
 * Spring AI 的 chat 模型会收集所有 ToolCallbackProvider 作为"LLM 可调用的工具"，
 * 而这些 tool 又依赖调用 LLM 的流水线，会形成循环依赖；它们本来也只给 MCP 客户端用，不给 DeepSeek 用。
 * C# 对照：≈ builder.Services.AddMcpServer().WithStdioServerTransport().WithTools&lt;McpTools&gt;()。
 */
@Configuration
public class McpServerConfig {

    @Bean
    public List<SyncToolSpecification> prReviewerTools(McpTools tools) {
        return McpToolUtils.toSyncToolSpecification(
                List.of(MethodToolCallbackProvider.builder().toolObjects(tools).build().getToolCallbacks()));
    }
}
