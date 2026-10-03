# PR Reviewer 设计文档（实现用）

> v1.4｜2026-10-02｜v1.4：类别加 LOGIC / PERF；标准答案格式扩展（多位置、跨文件、严重程度）；命中改为 ±3 行 + 一对一匹配；报告加 headSha / prFiles｜v1.3：报告新增 `errors`（ReviewError / ReviewStage），任何环节出错都可溯源｜v1.2：被审查语言由 C# 改为 Java；摘要上限 500 → 1500｜本文档供 AI 编码助手按里程碑实现。设计理由见 `pr-reviewer-design-notes.md`。

---

## 0. 给实现者（AI 助手）的说明

- **按第 12 节里程碑顺序实现**，每完成一个里程碑停下来，确认可运行、测试通过后再继续。
- **不要加本文档没要求的功能**（例如 Web UI、数据库）。第 13 节列了明确不做的事。
- 遇到文档与实际环境冲突时，先提出来，不要自行替换方案。
- 依赖版本以官方最新稳定版为准，**不要凭记忆写版本号**，先查官方文档确认。
- 被审查的目标代码是 **Java**（bookmarket 仓库，Spring Boot 项目），审查器本身也用 **Java** 实现。

---

## 1. 目标与范围

### 目标
对一个 GitHub PR 做代码审查，用 **map-reduce** 让每次 LLM 调用的上下文大小与 PR 规模无关：
- **Map**：每个文件单独一次 LLM 调用，同时按安全 / 逻辑 / 性能 / 风格 / 命名五套规则审查（v1.4 前为三套），输出结构化 JSON
- **Reduce**：用纯代码合并、去重、排序，不调用 LLM

另提供一个 **baseline 模式**（整个 PR 一次调用），用于对比实验。

### v1 范围内
- 输入：GitHub PR 链接
- 两种审查模式：`mapreduce`（主）和 `single`（baseline）
- 每次 LLM 调用记录 token 与耗时
- 评估：对照埋点 bug 清单计算召回
- **同一个 jar，两种启动方式**：命令行（主）和 MCP server
- 可选（M8，有余力再做）：审查结果写回 PR 评论

---

## 2. 技术栈

| 项 | 选择 |
|---|---|
| 语言 | Java 21 |
| 构建 | Maven |
| 框架 | Spring Boot 3 + Spring AI（chat client、MCP server starter） |
| LLM | OpenAI 兼容接口，base-url / model / api-key 全部走配置 |
| GitHub | GitHub REST API，用 Spring `RestClient` 直接调用 |
| JSON | Jackson |
| 测试 | JUnit 5 |

---

## 3. 核心概念：Skill、规则与 Tool 的对应

原西门子系统是 Agent + skill + PowerShell 编排。本项目的对应关系：

| 原系统 | 本项目 | 说明 |
|---|---|---|
| 三个 skill（风格/安全/命名） | 规则文件 `rules/style.md`、`security.md`、`naming.md`（v1.4 另加 `logic.md`、`perf.md`） | skill 是**说明书**，本质是 prompt 内容，不是 tool。每次 map 调用同时加载全部规则 |
| PowerShell 编排 | Java 流水线 `ReviewPipeline` | Workflow 式固定编排 |
| — | MCP tools | 只把**需要代码执行的操作**暴露为 tool（拉 PR 文件、单文件审查、汇总），不按审查类别拆 |

---

## 4. 架构与包结构

**核心原则：审查流水线是纯 Java 服务，与 CLI / MCP 解耦。** CLI 和 MCP 都只是入口层，调用同一套流水线。

```
com.lee.prreviewer
├── app/
│   ├── CliRunner             命令行入口
│   └── McpServerConfig       MCP server 入口
├── pipeline/
│   ├── ReviewPipeline        对外统一入口：review(request, mode)
│   ├── MapReduceReviewer     主模式
│   └── SingleCallReviewer    baseline 模式
├── github/
│   ├── GitHubPrClient        拉 PR 信息和文件列表
│   └── PrUrlParser           解析 PR 链接
├── preprocess/
│   ├── PatchParser           单文件 patch → 带行号的 FileDiff
│   ├── FileFilter            过滤规则
│   └── PrSummaryBuilder      生成 PR 摘要
├── map/
│   ├── FileReviewer          单文件一次 LLM 调用
│   └── PromptBuilder         组装 prompt（系统提示 + 五套规则 + PR 摘要 + diff）
├── reduce/
│   └── FindingAggregator     去重、排序（纯代码）
├── llm/
│   ├── LlmClient             封装调用 + 计量
│   └── CallMetrics
├── eval/
│   └── RecallEvaluator       对照埋点清单算召回
└── model/                    数据模型

resources/
├── prompts/system.md
└── rules/style.md, security.md, naming.md, logic.md, perf.md
```

### 流程

```
PR 链接 → PrUrlParser → ReviewRequest(owner, repo, prNumber)
  ▼
GitHubPrClient：拉 PR 文件列表（分页拉全）
  ▼
PatchParser → FileFilter → List<FileDiff>（无 patch 的文件进 skippedFiles）
  ▼
PrSummaryBuilder → PrSummary
  ▼
[mapreduce] 每个 FileDiff 并行 → FileReviewer → List<Finding>
[single]    全部 FileDiff 一次 → SingleCallReviewer → List<Finding>
  ▼
FindingAggregator → ReviewReport（含 metrics）
```

---

## 5. 数据模型

```java
record ReviewRequest(String owner, String repo, int prNumber) {}

enum ReviewMode { MAPREDUCE, SINGLE }

record FileDiff(
    String path,              // GitHub 返回的 filename
    ChangeType changeType,    // ADDED, MODIFIED, REMOVED, RENAMED
    List<Hunk> hunks,
    String annotatedDiff      // 带新文件行号的 diff 文本，直接喂给 LLM（见 6.2）
) {}

record Hunk(int newStart, int newCount, List<DiffLine> lines) {}
record DiffLine(LineType type, Integer newLineNo, String content) {} // ADDED / REMOVED / CONTEXT

record SkippedFile(String path, String reason) {}   // 例如：无 patch（过大或二进制）、被过滤规则排除

record PrSummary(
    String title,
    List<String> changedFiles,            // 路径 + 变更类型
    List<String> changedPublicSignatures  // 增/删的 public 方法、类、接口签名
) {
    String render();  // 渲染成简短文本，目标 < 1500 tokens
}

enum Category { STYLE, SECURITY, NAMING, LOGIC, PERF }   // v1.4 加入 LOGIC、PERF
enum Severity { HIGH, MEDIUM, LOW }

record Finding(
    String file,
    int line,              // 新文件中的行号
    Category category,
    Severity severity,
    String message,
    String suggestion
) {}

record CallMetrics(String label, int inputTokens, int outputTokens, long latencyMs, boolean success, int attempts) {}

// v1.3：出问题的环节，按流水线顺序
enum ReviewStage {
    PREPARE,             // 解析 PR 链接、拉取 GitHub、解析 patch；失败时整个审查无法进行
    LLM_CALL,            // 某一次 LLM API 调用尝试失败（超时、5xx、429、4xx 如超出上下文）
    LLM_OUTPUT,          // LLM 输出无法解析为 findings JSON，或结构不符
    FINDING_VALIDATION,  // finding 被丢弃：file 不是本次审查的文件，或行号不在改动范围
    INTERNAL             // 未预期的异常（通常是代码 bug）
}

// v1.3：审查过程中的一个问题，保证任何一步出错都能溯源到环节、文件和具体调用
record ReviewError(
    ReviewStage stage,
    String file,         // 涉及的文件；single 模式整次调用的问题为 "single"；PREPARE 为 null
    String callLabel,    // 相关 LLM 调用的 label（与 CallMetrics.label、日志一致，含 " [json-retry]"）；非 LLM 环节为 null
    boolean recovered,   // true = 已通过重试恢复，不影响结果；false = 导致文件失败或 finding 被丢弃
    String message       // 原始错误信息（异常类型 + 完整 cause 链）；LLM_OUTPUT 附 LLM 输出的开头
) {}

record ReviewReport(
    ReviewMode mode,
    String prUrl,
    String headSha,                  // v1.4：审查的 PR head commit；eval 用来核对和标准答案是否针对同一提交
    List<String> prFiles,            // v1.4：PR 全部文件（含被跳过的），GitHub 返回的顺序；eval 按它分三段
    List<Finding> findings,          // 已去重排序
    List<String> failedFiles,        // 重试后仍失败的文件；single 整次调用失败时为全部待审文件
    List<SkippedFile> skippedFiles,
    List<ReviewError> errors,        // v1.3：全部问题（含已恢复的），按发生顺序；预处理失败时只有这一项有内容
    List<CallMetrics> calls,
    long totalLatencyMs,             // 墙钟时间
    int totalInputTokens,
    int totalOutputTokens
) {}
```

`errors` 的记录规则：
- LLM 调用的**每一次失败尝试**都记一条 `LLM_CALL`（含最终重试成功的，`recovered = true`）
- 每次输出解析失败记一条 `LLM_OUTPUT`；JSON 重试后成功则首次失败 `recovered = true`
- 每条被丢弃的 finding 记一条 `FINDING_VALIDATION`，message 含 LLM 原始的 file / line / category / message
- 预处理失败不抛异常，返回只含一条 `PREPARE` 错误的报告（CLI 照常输出报告和 `--out` JSON，退出码 1）

---

## 6. 各模块详细设计

### 6.1 GitHubPrClient
- 认证：`Authorization: Bearer ${GITHUB_TOKEN}`，token 只从环境变量读
- `GET /repos/{owner}/{repo}/pulls/{n}`：拿 PR 标题、head commit sha
- `GET /repos/{owner}/{repo}/pulls/{n}/files?per_page=100&page=k`：**必须分页拉全**，直到返回空页
- 每个文件取 `filename`、`status`、`patch`
- **`patch` 字段可能缺失**（二进制文件，或改动过大 GitHub 不返回），此时记入 `skippedFiles` 并注明原因，不要报错
- 401 / 403 / 404 / 速率限制时抛出明确异常，信息里带上 HTTP 状态码和 GitHub 返回的 message

### 6.2 PatchParser
- GitHub 的 `patch` 是单个文件的 hunk 内容（以 `@@` 开头，没有文件头），解析为 `Hunk` 列表
- 生成 `annotatedDiff`：每行前加新文件行号，删除行用 `-` 占位：

```
File: src/main/java/com/example/demo/service/BookService.java
  41 |   public Book getById(Long id) {
  42 | +     log.info("get book {}", id);
   - | -     return bookRepository.getOne(id);
  43 | +     return bookRepository.findById(id).orElseThrow();
```

> 原因：LLM 必须能报出准确的行号，否则去重、评估、写回评论都做不了。

### 6.3 FileFilter
规则写在配置里，被跳过的文件记入 `skippedFiles` 并注明原因：
- `status = removed`
- 构建产物与自动生成代码（exclude-globs）：`target/`、`build/`、`generated/`、`generated-sources/`
- 只审查 Java 源文件（include-globs：`**/*.java`），配置、文档等其他文件跳过
- 无 patch，或仅重命名无内容改动

### 6.4 PrSummaryBuilder
- `title`：PR 标题
- `changedFiles`：过滤后的文件路径 + 变更类型
- `changedPublicSignatures`：**v1 用启发式**，在 ADDED / REMOVED 行里匹配 Java 的 `public` 方法 / 构造函数 / class / interface / enum / record / @interface 声明，标注新增或删除
- 不调用 LLM
- `render()` 超过上限时截断并注明"已截断"

### 6.5 FileReviewer（Map）
- 每个 `FileDiff` 一次调用，输入：系统提示 + 五套规则 + PR 摘要 + 该文件 `annotatedDiff`
- **并发**：固定大小线程池，并发数走配置（默认 4）
- **重试**：
  - API 错误 / 超时：指数退避，最多 2 次重试
  - 返回不是合法 JSON 或结构不符：带上错误信息重试 1 次
  - 仍失败：记入 `failedFiles`，**不影响其他文件**
- 只接受 `line` 落在该文件改动范围（ADDED 行或其相邻 CONTEXT 行）内的 finding，其余丢弃并记一条警告日志
- `file` 字段由代码填入，不让 LLM 输出

### 6.6 Prompt

`prompts/system.md` 要点：

```
你是一名 Java 代码审查员。只审查给出的 diff 中新增或修改的代码。
按照下面给出的五套规则（SECURITY / LOGIC / PERF / STYLE / NAMING）同时检查。
PR 摘要提供全局上下文，仅用于理解，不要审查摘要中提到但本次未给出 diff 的文件。
行号使用 diff 中左侧标注的新文件行号。
没有问题时返回空数组。只输出 JSON，不要任何其他文字。
```

`rules/*.md` 初始内容（后续可由用户替换为原 skill 的规则）：
- `style.md`：可读性、重复代码、过长方法、空 catch、未释放资源等
- `security.md`：SQL 注入、硬编码密钥、未校验输入、敏感信息写日志、不安全的反序列化等
- `naming.md`：Java 命名约定（类 PascalCase、方法/变量 camelCase、常量 UPPER_SNAKE_CASE、包名小写）、含义不清的名字、误导性命名
- `logic.md`（v1.4）：边界条件、包装类型比较与拆箱、状态流转校验、事务与代理自调用、金额精度、并发竞态
- `perf.md`（v1.4）：N+1、全量加载后内存过滤 / 分页、无上限查询、循环中的昂贵操作

> v1.4 起为五套规则（SECURITY / LOGIC / PERF / STYLE / NAMING）。此前逻辑 bug 只能归到 STYLE，类别一致率没有意义。
> 规则必须写成通用规则，**不能参照被审查 PR 的内容来写**，否则评估不可信。

输出格式：

```json
{
  "findings": [
    { "line": 43, "category": "SECURITY", "severity": "HIGH", "message": "...", "suggestion": "..." }
  ]
}
```

### 6.7 SingleCallReviewer（baseline）
- 一次调用，输入：同样的系统提示 + 五套规则 + PR 摘要 + **所有文件** `annotatedDiff` 拼接
- 输出格式同上，但每个 finding 需要 LLM 填 `file`
- 超出模型上下文时**不要截断**，直接记录失败和报错信息——"装不下"本身就是实验结果
- 整次调用失败时，`failedFiles` 为全部待审文件，报错信息记入 `errors`
- 重试策略与 map 相同（API 错误指数退避 + 非法 JSON 重试 1 次）；LLM 填的 `file` 不是本次审查的文件、或行号不在该文件改动范围的 finding 丢弃并记入 `errors`

### 6.8 FindingAggregator（Reduce，纯代码）
1. 合并所有 finding
2. 去重键：`file + line + category`，重复时保留 severity 最高的一条
3. 排序：severity（HIGH → LOW）→ file → line

### 6.9 LlmClient
- 统一封装所有 LLM 调用，每次调用产出一条 `CallMetrics`
- token 数取 API 返回的 usage 字段，**不要自己估算**
- 每次调用写一条结构化日志：label（文件路径或 `single`）、input/output tokens、耗时、是否成功、重试次数

---

## 7. 启动方式（同一个 jar）

### 7.1 命令行（v1 主要方式）

```
java -jar pr-reviewer.jar review --pr <PR链接> --mode mapreduce|single [--out report.json]
java -jar pr-reviewer.jar eval --report report.json --truth ground_truth.json
```

- 不启动 Web 服务（`spring.main.web-application-type=none`）
- `review` 运行时控制台实时打印进度：`[12/53] service/BookService.java  in=1820 out=240  3.1s  ✓`
- 结束时打印汇总：finding 数（按严重程度）、失败 / 跳过文件数、总 token、总耗时；同时输出一份可读的 Markdown 报告
- `--out` 指定时写出完整 `ReviewReport` JSON
- `eval` 打印召回结果（见第 9 节）

### 7.2 MCP server

```
java -jar pr-reviewer.jar mcp
```

- stdio 传输，由 MCP 客户端（VSCode Copilot Agent 模式、Claude Desktop 等）启动
- **stdio 模式下 stdout 是协议通道**：必须关闭 Spring banner 和控制台日志，所有日志写到 stderr 或文件，否则会破坏协议
- 提供一份 MCP 客户端配置示例（command + args + 环境变量）写在 README

Tools（按"需要代码执行的操作"拆，不按审查类别拆）：

| Tool | 输入 | 输出 | 说明 |
|---|---|---|---|
| `review_pr` | prUrl, mode | ReviewReport | 一键完整审查，Workflow 式编排 |
| `list_pr_files` | prUrl | 过滤后的文件列表、跳过列表、PR 摘要 | 预处理 |
| `review_file` | prUrl, filePath | List<Finding> | 单文件 map |
| `aggregate_findings` | List<Finding> | 去重排序后的 List<Finding> | reduce |

后三个 tool 让外部 Agent 也能自己编排 map-reduce。

---

## 8. 配置（application.yml 示例结构）

```yaml
spring:
  main:
    web-application-type: none
llm:
  base-url: ${LLM_BASE_URL}
  api-key: ${LLM_API_KEY}
  model: ${LLM_MODEL}
  timeout-seconds: 120
  temperature: 0          # v1.3：固定，保证同一 PR 多次审查结果尽量一致（召回对比需要）
github:
  api-base: https://api.github.com
  token: ${GITHUB_TOKEN}
review:
  concurrency: 4
  max-retries: 2
  summary-max-tokens: 1500
  filter:
    include-globs: ["**/*.java"]
    exclude-globs: ["**/target/**", "**/build/**", "**/generated/**", "**/generated-sources/**"]
```

所有密钥只从环境变量读，**不要写进任何提交的文件**。启动时缺少必需配置直接报错退出。

---

## 9. 评估

### 埋点清单格式 `ground_truth.json`（v1.4 扩展）

```json
{
  "prUrl": "https://github.com/DavidLee617/bookmarket/pull/1",
  "headSha": "7fd23c0c1b04ab126d10459e5c0dc4de6d15624c",
  "source": "答案来源说明",
  "bugs": [
    { "id": "B11", "severity": "HIGH", "crossFile": true, "categories": ["LOGIC"],
      "locations": [
        { "file": "src/main/java/com/example/demo/dto/OrderItemRequest.java", "lineStart": 15, "lineEnd": 15 },
        { "file": "src/main/java/com/example/demo/service/OrderService.java", "lineStart": 56, "lineEnd": 56 }],
      "note": "int 改 Integer，不传 quantity 时拆箱 NPE" }
  ]
}
```

- `file` 与 GitHub 返回的 `filename` 一致；行号是 `headSha` 版本的新文件行号
- `locations`：跨文件的雷有多个位置，命中任一即算命中；第一个为主位置，决定分段
- `categories`：可接受的类别（一个问题可能同时属于多类），只用于类别一致率
- `crossFile`：单看一个文件发现不了的雷，单独统计召回，用来测 map-reduce 的已知弱点
- `prUrl` / `headSha` 与报告不一致时 eval 给出警告

### 匹配规则（v1.4）
- **候选**：finding 的 `file` 与埋点任一位置相同，且 `line` ∈ `[lineStart - 3, lineEnd + 3]`
- **一对一**：一条 finding 最多命中一个埋点。候选配对按 类别是否一致 → 行距 → 埋点顺序 → finding 顺序 排序后依次配对，已用过的跳过
  - 原因：埋点密集时 ±3 窗口重叠，允许一条 finding 命中多个埋点会高估召回（PR #1 实测：一条"库存 `<=`"的 finding 同时"命中"了相邻的拆箱 NPE 埋点）
  - 类别优先于行距：避免配到相邻行的无关 finding；副作用是类别一致率偏乐观
- category 不影响是否命中（单独统计一致率）
- "问题本质是否一致"代码判断不了：eval 列出每个埋点配到的 finding 原文，供人工复核

### 输出
- 召回 = 命中数 / 埋点总数；另给"审查范围内"（排除文件被过滤规则跳过的埋点）、单文件雷 / 跨文件雷、按严重程度的召回
- 每个埋点的命中情况和配到的 finding
- **按文件在 PR 文件列表（报告的 `prFiles`）中的位置分成前 / 中 / 后三段，分别统计召回**——验证"单次调用时靠后的文件审得更差"
- 类别一致率（命中的埋点中）
- 未匹配任何埋点的 finding 及数量（仅参考，不等于误报）

---

## 10. 错误处理与测试

### 错误处理原则
- 单个文件失败不影响整体，报告里列出 `failedFiles` 和 `skippedFiles`
- 异常保留原始原因，不要吞掉或替换成笼统提示
- 任何环节出的问题都写进报告的 `errors`（见第 5 节），包括已被重试恢复的，保证可以从报告溯源到环节、文件和具体调用

### 测试要求
- `PrUrlParser`：合法 / 非法链接
- `PatchParser`：多 hunk、纯新增文件、行号标注正确
- `FileFilter`：每条排除规则至少一个用例
- `PrSummaryBuilder`：识别新增和删除的 public 签名
- `FindingAggregator`：去重保留最高 severity、排序正确
- `RecallEvaluator`：±2 行边界
- `GitHubPrClient`：用 mock 服务器测试分页、`patch` 缺失
- `FileReviewer`：用假的 `LlmClient` 测试重试和非法 JSON
- **单元测试不调真实 LLM 和真实 GitHub**

---

## 11. 测试数据准备（人工完成，不由 AI 助手生成）

- 把 bookmarket 推到 GitHub
- 建一个 50+ 文件改动的分支并提 PR
- 埋点 bug 分散在 PR 文件列表的前、中、后三段
- 手工维护 `ground_truth.json`

> 埋点答案不能由同一个模型生成，否则评估不可信。

---

## 12. 里程碑

| # | 内容 | 验收 |
|---|---|---|
| M1 | 项目骨架、配置、`model` 包、`LlmClient` + 计量 | 能调通一次 LLM 并打印 token 和耗时 |
| M2 | `PrUrlParser`、`GitHubPrClient`、`PatchParser`、`FileFilter`、`PrSummaryBuilder` + 单测 | 给一个 PR 链接，输出文件列表、跳过列表和 PR 摘要 |
| M3 | `FileReviewer` + prompt + 规则文件 + 重试 | 单个文件能稳定输出合法 findings |
| M4 | `MapReduceReviewer` + `FindingAggregator` + CLI `review` | mapreduce 模式跑通一个多文件 PR，控制台有进度，输出完整报告 |
| M5 | `SingleCallReviewer` | single 模式跑通同一个 PR |
| M6 | `RecallEvaluator` + CLI `eval` | 两种模式都能算出召回和分段召回 |
| M7 | MCP server 四个 tool | 能从 MCP 客户端调用 `review_pr` |
| M8（可选） | 结果写回 PR 评论 | 见下 |

M1–M3 对应计划第 3 天，M4–M6 对应第 4 天，M7 视进度，M8 有余力再做。

### M8 写回 PR 评论（可选）
- 用 `POST /repos/{owner}/{repo}/pulls/{n}/reviews` 一次提交一个 review，包含多条行内评论
- 每条评论需要 `path`、`line`、`side: RIGHT`，以及 PR 的 head `commit_id`
- 行内评论的行必须在该 PR 的 diff 范围内，否则 GitHub 会拒绝；不在范围内的 finding 汇总写进 review 正文
- CLI 加 `--post-comments` 开关，默认关闭

---

## 13. 明确不做（v1）

- Web UI、数据库、持久化
- GitHub Action / webhook 自动触发（v2 演进方向）
- 按 token 预算装箱、大文件按 hunk 拆分（v2）
- Reduce 阶段调用 LLM 生成整体评价（v2）
- Agent 自主决策调用顺序（本项目刻意采用 Workflow 式编排）
- 跨文件问题的专门分析（v1 只靠 PR 摘要弥补）
