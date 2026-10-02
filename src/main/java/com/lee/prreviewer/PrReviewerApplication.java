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
        ConfigurableApplicationContext context = SpringApplication.run(PrReviewerApplication.class, args);
        // 命令行模式：CliRunner 跑完即退出，退出码来自 CliRunner（ExitCodeGenerator）
        // MCP 模式（M7）需要常驻，不在这里退出
        boolean mcpMode = args.length > 0 && "mcp".equals(args[0]);
        if (!mcpMode) {
            System.exit(SpringApplication.exit(context));
        }
    }
}
