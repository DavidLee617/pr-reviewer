package com.lee.prreviewer.app;

import com.lee.prreviewer.github.GitHubApiException;
import com.lee.prreviewer.llm.CallMetrics;
import com.lee.prreviewer.llm.LlmCallException;
import com.lee.prreviewer.llm.LlmClient;
import com.lee.prreviewer.llm.LlmResponse;
import com.lee.prreviewer.map.FileReviewResult;
import com.lee.prreviewer.model.Finding;
import com.lee.prreviewer.model.FileDiff;
import com.lee.prreviewer.model.LineType;
import com.lee.prreviewer.model.PreparedPr;
import com.lee.prreviewer.model.SkippedFile;
import com.lee.prreviewer.pipeline.ReviewPipeline;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

/**
 * 命令行入口。stdout 只输出给人看的结果，日志走 stderr（见 logback-spring.xml）。
 * <p>
 * C# 对照：CommandLineRunner ≈ 在 Program.cs 里 host 构建好之后执行的那段逻辑
 * （或一个跑完即结束的 BackgroundService）；ExitCodeGenerator ≈ 设置 Environment.ExitCode。
 */
@Component
public class CliRunner implements CommandLineRunner, ExitCodeGenerator {

    private static final String USAGE = """
            用法:
              java -jar pr-reviewer.jar ping                                   测试 LLM 连通性（打印 token 和耗时）
              java -jar pr-reviewer.jar files --pr <PR链接> [--diff]           预处理：文件列表、跳过列表、PR 摘要
              java -jar pr-reviewer.jar review-file --pr <PR链接> --file <路径>   审查单个文件
              java -jar pr-reviewer.jar review --pr <PR链接> --mode mapreduce|single [--out report.json]   (M4)
              java -jar pr-reviewer.jar eval --report report.json --truth ground_truth.json             (M6)
              java -jar pr-reviewer.jar mcp                                    以 MCP stdio server 启动 (M7)
            """; // ≈ C# 11 的原始字符串字面量 """..."""

    private final LlmClient llmClient;
    private final ReviewPipeline pipeline;
    private int exitCode = 0;

    public CliRunner(LlmClient llmClient, ReviewPipeline pipeline) {
        this.llmClient = llmClient;
        this.pipeline = pipeline;
    }

    @Override
    public void run(String... args) {
        if (args.length == 0) {
            System.out.print(USAGE);
            exitCode = 2;
            return;
        }
        // switch 表达式 ≈ C# 8 的 switch expression
        exitCode = switch (args[0]) {
            case "ping" -> ping();
            case "files" -> files(args);
            case "review-file" -> reviewFile(args);
            case "review", "eval", "mcp" -> notYet(args[0]);
            default -> {
                System.out.println("未知命令: " + args[0]);
                System.out.print(USAGE);
                yield 2;
            }
        };
    }

    /** M1 验收：调通一次 LLM，打印回复、token 和耗时。 */
    private int ping() {
        try {
            LlmResponse response = llmClient.call("ping",
                    "You are a connectivity check. Follow the instruction exactly.",
                    "Reply with the single word: pong");
            CallMetrics m = response.metrics();
            System.out.printf("reply=%s%nin=%d out=%d  %.1fs  attempts=%d  ✓%n",
                    response.content().strip(), m.inputTokens(), m.outputTokens(),
                    m.latencyMs() / 1000.0, m.attempts());
            return 0;
        } catch (LlmCallException e) {
            // 保留原始原因，不替换成笼统提示
            System.out.println("✗ " + e.getMessage());
            return 1;
        }
    }

    /** M2 验收：给一个 PR 链接，输出文件列表、跳过列表和 PR 摘要。--diff 时额外打印每个文件的 annotatedDiff。 */
    private int files(String[] args) {
        String prUrl = option(args, "--pr");
        if (prUrl == null) {
            System.out.println("缺少参数 --pr <PR链接>");
            return 2;
        }
        try {
            PreparedPr pr = pipeline.prepare(prUrl);

            System.out.printf("== 待审查文件 (%d)%n", pr.files().size());
            for (FileDiff f : pr.files()) {
                long added = f.hunks().stream()
                        .flatMap(h -> h.lines().stream())
                        .filter(l -> l.type() == LineType.ADDED)
                        .count();
                System.out.printf("  %-60s %-8s hunks=%d  +%d%n", f.path(), f.changeType(), f.hunks().size(), added);
            }
            System.out.printf("%n== 跳过文件 (%d)%n", pr.skippedFiles().size());
            for (SkippedFile s : pr.skippedFiles()) {
                System.out.printf("  %-60s %s%n", s.path(), s.reason());
            }
            System.out.printf("%n== PR 摘要%n%s", pr.summary().render());

            if (hasFlag(args, "--diff")) {
                System.out.printf("%n== annotatedDiff%n");
                pr.files().forEach(f -> System.out.println(f.annotatedDiff()));
            }
            return 0;
        } catch (IllegalArgumentException | GitHubApiException e) { // ≈ C# 的 catch (Exception e) when (e is A or B)
            System.out.println("✗ " + e.getMessage());
            return 1;
        }
    }

    /** M3 验收：审查单个文件，打印 findings 和每次 LLM 调用的计量。 */
    private int reviewFile(String[] args) {
        String prUrl = option(args, "--pr");
        String file = option(args, "--file");
        if (prUrl == null || file == null) {
            System.out.println("缺少参数：review-file --pr <PR链接> --file <路径>");
            return 2;
        }
        try {
            FileReviewResult result = pipeline.reviewFile(prUrl, file);
            for (CallMetrics m : result.calls()) {
                System.out.printf("%s  in=%d out=%d  %.1fs  attempts=%d  %s%n", m.label(), m.inputTokens(),
                        m.outputTokens(), m.latencyMs() / 1000.0, m.attempts(), m.success() ? "✓" : "✗");
            }
            if (result.failed()) {
                System.out.println("✗ " + result.error());
                return 1;
            }
            System.out.printf("%n== findings (%d)%n", result.findings().size());
            for (Finding f : result.findings()) {
                System.out.printf("[%s][%s] 第 %d 行：%s%n    建议：%s%n",
                        f.severity(), f.category(), f.line(), f.message(), f.suggestion());
            }
            return 0;
        } catch (IllegalArgumentException | GitHubApiException e) {
            System.out.println("✗ " + e.getMessage());
            return 1;
        }
    }

    private int notYet(String command) {
        System.out.println("命令 '" + command + "' 尚未实现（见设计文档第 12 节里程碑）");
        return 2;
    }

    /** 取 "--name value" 形式的参数值，没有则返回 null。 */
    private static String option(String[] args, String name) {
        for (int i = 0; i < args.length - 1; i++) {
            if (name.equals(args[i])) {
                return args[i + 1];
            }
        }
        return null;
    }

    private static boolean hasFlag(String[] args, String name) {
        for (String a : args) {
            if (name.equals(a)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
