# pr-reviewer

用 map-reduce 方式审查 Java 仓库（bookmarket）的 GitHub PR。设计见 `DESIGN.md`（被审查语言已由 C# 改为 Java）。

代码注释里的「C# 对照」说明的是 Java/Spring 概念在 C#/.NET 中的对应写法，方便有 C# 背景的读者理解。

当前进度：**M7**（mapreduce / single 两种模式 + 报告错误溯源 + 召回评估 + MCP server）。

审查规则在 `src/main/resources/rules/`（security / logic / perf / style / naming，对应五个类别），系统提示在 `src/main/resources/prompts/system.md`，可直接修改。

## 环境

- JDK 21（`java -version` 确认；Spring Boot 3.5 最高支持到 25，不要用 26）、Maven 3.9+
- 技术栈：Spring Boot 3.5.16 + Spring AI 1.1.8

## 环境变量（必需，缺失时启动即报错退出）

| 变量 | 说明 |
|---|---|
| `LLM_BASE_URL` | OpenAI 兼容接口地址，**不带 `/v1`**，例如 `https://api.openai.com`（Spring AI 会自动拼 `/v1/chat/completions`） |
| `LLM_API_KEY` | LLM API key |
| `LLM_MODEL` | 模型名 |
| `GITHUB_TOKEN` | GitHub token（M2 起需要） |

也可以写在工作目录下的 `config/application.yml`（已在 `.gitignore` 中，不会提交）：

```yaml
llm:
  base-url: https://api.deepseek.com
  api-key: sk-...
  model: deepseek-chat
github:
  token: github_pat_...
```

临时设置环境变量（仅当前窗口）：

```bash
# macOS / Linux
export LLM_BASE_URL=https://api.openai.com LLM_API_KEY=sk-... LLM_MODEL=gpt-4o-mini
```

```powershell
# Windows PowerShell
$env:LLM_BASE_URL = "https://api.openai.com"
$env:LLM_API_KEY  = "sk-..."
$env:LLM_MODEL    = "gpt-4o-mini"
```

## 构建与运行

```bash
mvn package                                  # 编译 + 单元测试（不调真实 LLM / GitHub）
java -jar target/pr-reviewer.jar ping        # 调通一次 LLM，打印 token 和耗时
java -jar target/pr-reviewer.jar files --pr <PR链接> [--diff]   # 文件列表、跳过列表、PR 摘要
java -jar target/pr-reviewer.jar review-file --pr <PR链接> --file OrderService.java   # 审查单个文件
java -jar target/pr-reviewer.jar review --pr <PR链接> [--mode mapreduce|single] [--out report.json]   # 完整审查
java -jar target/pr-reviewer.jar eval --report report.json --truth ground_truth.json   # 对照标准答案算召回
```

日志写 stderr 和 `~/.pr-reviewer/pr-reviewer.log`，结果写 stdout。

`review` 的 stdout 依次是：实时进度 → Markdown 报告 → 汇总；`--out` 另写完整 `ReviewReport` JSON。

- `mapreduce`（默认）：每个文件一次 LLM 调用，并发 `review.concurrency` 个
- `single`（baseline）：整个 PR 一次调用；超出模型上下文时直接失败、不截断

**问题追踪**：报告的 `errors` 记录过程中所有问题，每条写明环节（`PREPARE` / `LLM_CALL` / `LLM_OUTPUT` / `FINDING_VALIDATION` / `INTERNAL`）、文件、LLM 调用 label（与 stderr 日志一致）、是否已被重试恢复、原始错误信息。预处理失败（链接非法、GitHub 报错）时也照常输出报告和 JSON。预处理失败或有文件审查失败时退出码为 1。

## 召回评估（eval）

`ground_truth.json` 是人工维护的标准答案：PR 里故意埋的 bug 在哪个文件、哪几行、属于什么类别。格式见 `config/DESIGN.md` 第 9 节，仓库根目录下的 `ground_truth.json` 是 bookmarket PR #1 的答案。**答案不能由 AI 生成**，否则评估不可信。

- 命中：finding 与埋点同文件且行号在 ±3 行内；一条 finding 最多命中一个埋点（埋点密集时避免高估）
- 输出：总召回、审查范围内召回、单文件 / 跨文件召回、按严重程度召回、按 PR 文件顺序分前 / 中 / 后三段的召回、类别一致率、每个埋点配到的 finding 原文（供人工复核"问题本质是否一致"）、未匹配任何埋点的 finding
- 报告与答案的 PR 或提交不一致时会给出警告

```
[ 5/11] controller/OrderController.java   in=2833 out=263  1.9s  ✓ 3 findings
```

进度按完成顺序编号；路径省略了所有文件共同的目录前缀（首行会打印该前缀）；`in/out/耗时` 是该文件所有调用（含 JSON 重试）的合计。

## MCP server

同一个 jar 用 `mcp` 命令以 MCP stdio server 启动，由 MCP 客户端拉起，不要手动在终端里运行。

| Tool | 参数 | 返回 |
|---|---|---|
| `review_pr` | `prUrl`，`mode`（可选，`mapreduce` / `single`） | 完整 `ReviewReport`（findings、失败 / 跳过文件、errors、计量） |
| `list_pr_files` | `prUrl` | 待审文件（路径、变更类型、hunk 数、新增行数）、跳过文件、PR 摘要；不含 diff 正文 |
| `review_file` | `prUrl`，`filePath`（完整路径或唯一后缀） | 该文件的 findings、LLM 调用计量、errors |
| `aggregate_findings` | `findings` | 去重排序后的 findings |

后三个 tool 让外部 Agent 自己编排 map-reduce。

客户端配置要点（GUI 应用不会读 `~/.zshenv`，工作目录也不是本项目）：
- `command` 写 JDK 21 的绝对路径
- 用 `--spring.config.additional-location` 指向本项目的 `config/` 目录，密钥就不用写进客户端配置；也可以改用 `env` 传 `LLM_*` / `GITHUB_TOKEN`

Claude Desktop（`~/Library/Application Support/Claude/claude_desktop_config.json`）：

```json
{
  "mcpServers": {
    "pr-reviewer": {
      "command": "/opt/homebrew/opt/openjdk@21/bin/java",
      "args": [
        "-jar", "/Users/lee/Documents/spring/pr-reviewer/target/pr-reviewer.jar", "mcp",
        "--spring.config.additional-location=file:/Users/lee/Documents/spring/pr-reviewer/config/"
      ]
    }
  }
}
```

VSCode（工作区 `.vscode/mcp.json`，Copilot Agent 模式）：

```json
{
  "servers": {
    "pr-reviewer": {
      "type": "stdio",
      "command": "/opt/homebrew/opt/openjdk@21/bin/java",
      "args": [
        "-jar", "/Users/lee/Documents/spring/pr-reviewer/target/pr-reviewer.jar", "mcp",
        "--spring.config.additional-location=file:/Users/lee/Documents/spring/pr-reviewer/config/"
      ]
    }
  }
}
```

Claude Code：

```bash
claude mcp add pr-reviewer -- /opt/homebrew/opt/openjdk@21/bin/java -jar /Users/lee/Documents/spring/pr-reviewer/target/pr-reviewer.jar mcp --spring.config.additional-location=file:/Users/lee/Documents/spring/pr-reviewer/config/
```

**实时查看进度和 token 消耗**：MCP 模式下 jar 由客户端在后台拉起，stderr 看不到。日志同时写在 `~/.pr-reviewer/pr-reviewer.log`（CLI 模式也写），另开一个终端：

```bash
tail -f ~/.pr-reviewer/pr-reviewer.log | grep -E "review_start|file_reviewed|review_done|llm_call|WARN|ERROR"
```

- `review_start`：开始审查，待审 / 跳过文件数
- `file_reviewed [3/11] file=... findings=... inputTokens=... outputTokens=... latencyMs=...`：每个文件完成一条（含 JSON 重试的合计）
- `llm_call label=... inputTokens=... outputTokens=... latencyMs=... attempts=...`：每次 LLM 调用一条（含失败和重试）
- `review_done ... inputTokens=... outputTokens=... wallMs=...`：整次审查的合计
- 每行带 `[pid N]`，多个进程（MCP 会话、终端里的 CLI）同时写时可以区分；日志路径可用 `--logging.file.name=...` 修改

说明：
- 日志写 stderr 和上面的日志文件，stdout 只走 MCP 协议；MCP server 只在 `mcp` 命令下启用，其他 CLI 命令不受影响
- `review_pr` 对大 PR 可能要几十秒到几分钟，部分客户端的 tool 超时较短，必要时调大客户端超时
- 协议版本为 `2024-11-05`（MCP Java SDK 0.18.3 的 stdio 传输），客户端会自动协商

## C# 开发者对照速查

| Java / Spring | C# / .NET |
|---|---|
| `pom.xml` / Maven Central | `.csproj` / NuGet |
| `record` | `record`（访问器是 `name()` 而非属性 `Name`） |
| `@Component` + 构造函数注入 | `services.AddSingleton<T>()` + 构造函数注入 |
| `@ConfigurationProperties` + `@Validated` | `IOptions<T>` + `ValidateDataAnnotations().ValidateOnStart()` |
| `application.yml` | `appsettings.json` |
| `CommandLineRunner` | Program.cs 中 host 构建后的执行逻辑 |
| SLF4J `Logger` | `ILogger<T>` |
| Spring AI `ChatClient` | Microsoft.Extensions.AI `IChatClient` |
| Spring `RestClient` | `HttpClient` |
| JUnit 5 + AssertJ | xUnit + FluentAssertions |
