# pr-reviewer

用 map-reduce 方式审查 Java 仓库（bookmarket）的 GitHub PR。设计见 `DESIGN.md`（被审查语言已由 C# 改为 Java）。

代码注释里的「C# 对照」说明的是 Java/Spring 概念在 C#/.NET 中的对应写法，方便有 C# 背景的读者理解。

当前进度：**M4**（并发 map + 去重汇总 + CLI `review` + 报告）。

审查规则在 `src/main/resources/rules/`（style / security / naming），系统提示在 `src/main/resources/prompts/system.md`，可直接修改。

## 环境

- JDK 21（`java -version` 确认）、Maven 3.9+
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
java -jar target/pr-reviewer.jar review --pr <PR链接> [--mode mapreduce] [--out report.json]   # 完整审查
```

日志写 stderr，结果写 stdout。

`review` 的 stdout 依次是：实时进度 → Markdown 报告 → 汇总；`--out` 另写完整 `ReviewReport` JSON。有文件审查失败时退出码为 1（报告仍完整输出）。

```
[ 5/11] controller/OrderController.java   in=2833 out=263  1.9s  ✓ 3 findings
```

进度按完成顺序编号；路径省略了所有文件共同的目录前缀（首行会打印该前缀）；`in/out/耗时` 是该文件所有调用（含 JSON 重试）的合计。

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
