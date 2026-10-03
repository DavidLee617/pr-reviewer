package com.lee.prreviewer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 程序入口。
 * C# 对照：≈ Program.cs 里的 Host.CreateApplicationBuilder(args).Build().Run()。
 * {@code @SpringBootApplication} 会扫描本包及子包下的 @Component 并注册到 DI 容器
 * （≈ 手动写一堆 services.AddSingleton&lt;T&gt;()）。
 */
@SpringBootApplication
@ConfigurationPropertiesScan // 自动注册所有 @ConfigurationProperties（≈ services.Configure<T>(config.GetSection(...))）
public class PrReviewerApplication {

    public static void main(String[] args) {
        boolean mcpMode = args.length > 0 && "mcp".equals(args[0]);
        if (mcpMode) {
            // application.yml 里 MCP server 默认关闭；系统属性优先级高于 application.yml，只在 mcp 命令下打开
            System.setProperty("spring.ai.mcp.server.enabled", "true");
        }
        ConfigurableApplicationContext context = SpringApplication.run(PrReviewerApplication.class, args);
        // 命令行模式：CliRunner 跑完即退出，退出码来自 CliRunner（ExitCodeGenerator）
        // MCP 模式：常驻，由 stdio 传输线程读 stdin，客户端关闭 stdin 后进程结束
        if (!mcpMode) {
            System.exit(SpringApplication.exit(context));
        }
    }
}
