package com.lee.prreviewer.app;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.lee.prreviewer.model.ReviewMode;
import com.lee.prreviewer.model.ReviewReport;
import com.lee.prreviewer.model.ReviewStage;
import com.lee.prreviewer.model.SkippedFile;
import com.lee.prreviewer.pipeline.ReviewPipeline;
import com.lee.prreviewer.pipeline.ReviewProgressListener;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
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
              java -jar pr-reviewer.jar review --pr <PR链接> [--mode mapreduce|single] [--out report.json]   完整审查（默认 mapreduce）
              java -jar pr-reviewer.jar eval --report report.json --truth ground_truth.json             (M6)
              java -jar pr-reviewer.jar mcp                                    以 MCP stdio server 启动 (M7)
            """; // ≈ C# 11 的原始字符串字面量 """..."""

    private final LlmClient llmClient;
    private final ReviewPipeline pipeline;
    private final ObjectMapper json;
    private int exitCode = 0;

    public CliRunner(LlmClient llmClient, ReviewPipeline pipeline, ObjectMapper json) {
        this.llmClient = llmClient;
        this.pipeline = pipeline;
        this.json = json;
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
            case "review" -> review(args);
            case "eval", "mcp" -> notYet(args[0]);
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

    /**
     * 完整审查。stdout 依次输出：实时进度 → Markdown 报告 → 汇总；--out 时另写完整 JSON。
     * 预处理失败（链接非法、GitHub 报错）也会输出报告和 JSON，错误在"问题追踪"里。
     * 预处理失败或有文件审查失败时退出码为 1（报告仍完整输出）。
     */
    private int review(String[] args) {
        String prUrl = option(args, "--pr");
        if (prUrl == null) {
            System.out.println("缺少参数：review --pr <PR链接> [--mode mapreduce|single] [--out report.json]");
            return 2;
        }
        String modeArg = Optional.ofNullable(option(args, "--mode")).orElse("mapreduce");
        ReviewMode mode;
        try {
            mode = ReviewMode.valueOf(modeArg.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            System.out.println("--mode 只能是 mapreduce 或 single，收到: " + modeArg);
            return 2;
        }
        String out = option(args, "--out");

        ConsoleProgress progress = new ConsoleProgress(mode);
        ReviewReport report = pipeline.review(prUrl, mode, progress);

        System.out.println();
        System.out.print(MarkdownReport.render(report));

        System.out.printf("%n== 汇总%n");
        System.out.printf("findings  %d（%s）%n", report.findings().size(), MarkdownReport.severityCounts(report.findings()));
        System.out.printf("文件      审查 %d · 失败 %d · 跳过 %d%n",
                progress.total, report.failedFiles().size(), report.skippedFiles().size());
        System.out.printf("LLM       %d 次调用 · in=%d out=%d%n",
                report.calls().size(), report.totalInputTokens(), report.totalOutputTokens());
        System.out.printf("问题追踪  %s%n", MarkdownReport.errorCounts(report.errors()));
        System.out.printf("耗时      %.1fs（墙钟）%n", report.totalLatencyMs() / 1000.0);

        if (out != null) {
            try {
                json.writerWithDefaultPrettyPrinter().writeValue(Path.of(out).toFile(), report);
                System.out.println("已写出 " + Path.of(out).toAbsolutePath());
            } catch (IOException e) {
                System.out.println("✗ 写出 " + out + " 失败: " + e);
                return 1;
            }
        }
        boolean prepareFailed = report.errors().stream().anyMatch(e -> e.stage() == ReviewStage.PREPARE);
        return prepareFailed || !report.failedFiles().isEmpty() ? 1 : 0;
    }

    /**
     * 打印 [3/11] service/OrderService.java  in=3242 out=909  4.5s  ✓ 形式的进度行。
     * single 模式只有一行：[1/1] single（11 个文件）…
     */
    private static final class ConsoleProgress implements ReviewProgressListener {
        private final ReviewMode mode;
        private int total;
        private String commonPrefix = "";
        private int pathWidth;

        ConsoleProgress(ReviewMode mode) {
            this.mode = mode;
        }

        @Override
        public void onPrepared(PreparedPr pr) {
            total = pr.files().size();
            if (mode == ReviewMode.SINGLE) {
                System.out.printf("审查 %d 个文件（跳过 %d），single 模式：一次调用，请稍候…%n",
                        total, pr.skippedFiles().size());
                return;
            }
            List<String> paths = pr.files().stream().map(FileDiff::path).toList();
            commonPrefix = commonDirPrefix(paths);
            pathWidth = paths.stream().mapToInt(p -> p.length() - commonPrefix.length()).max().orElse(0);
            System.out.printf("审查 %d 个文件（跳过 %d）%s%n", total, pr.skippedFiles().size(),
                    commonPrefix.isEmpty() ? "" : "，路径相对 " + commonPrefix);
        }

        @Override
        public void onFileReviewed(int done, int total, FileReviewResult r) {
            String name = mode == ReviewMode.SINGLE
                    ? r.file() + "（" + this.total + " 个文件）"
                    : r.file().substring(commonPrefix.length());
            // 一个文件可能有多次调用（JSON 重试），合计显示
            int in = r.calls().stream().mapToInt(CallMetrics::inputTokens).sum();
            int outTokens = r.calls().stream().mapToInt(CallMetrics::outputTokens).sum();
            long ms = r.calls().stream().mapToLong(CallMetrics::latencyMs).sum();
            String width = String.valueOf(String.valueOf(total).length());
            String status = r.failed() ? "✗ " + r.error() : "✓ " + r.findings().size() + " findings";
            System.out.printf("[%" + width + "d/%d] %-" + Math.max(pathWidth, 1) + "s  in=%d out=%d  %.1fs  %s%n",
                    done, total, name, in, outTokens, ms / 1000.0, status);
        }

        /** 所有路径共同的目录前缀（以 / 结尾），用于缩短进度行。 */
        static String commonDirPrefix(List<String> paths) {
            if (paths.size() < 2) {
                return "";
            }
            String prefix = paths.get(0);
            for (String p : paths) {
                while (!p.startsWith(prefix)) {
                    prefix = prefix.substring(0, prefix.length() - 1);
                }
            }
            return prefix.substring(0, prefix.lastIndexOf('/') + 1);
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
