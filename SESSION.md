# pr-reviewer 交接文档

> 截至 2026-10-02。本文件是新会话的起点。`SESSION-history.md` 是更早的开发流水账，仅作历史保留，内容已全部并入本文件，不必再读。
> 读完本文件和第 1 节列出的文件，就能接手当前进度。

---

## 0. 给新会话的工作约定

- **一律用中文回答**，包括总结和进度说明。代码标识符、命令、文件名保持原样。
- 用户有 C# / .NET 背景。代码注释里的「C# 对照」是给用户看的，新写的代码保持这个风格（注释密度和措辞与周围一致）。
- `config/DESIGN.md` 是需求来源：按里程碑实现；**不加设计没要求的功能**；文档与实际环境冲突时先提出来，不要自行替换；依赖版本先查 Maven Central，**不要凭记忆写版本号**。
- 修改了设计（数据模型、规则、匹配口径等）要同步更新 `config/DESIGN.md`，并在本文件的"决定"表里记一笔原因。
- **密钥**：`config/application.yml` 里有 DeepSeek key 和 GitHub token，已被 `.gitignore` 排除。不要打印、不要提交、不要写进任何其他文件；用户如果在对话里贴密钥，提醒他直接改文件。
- **git**：当前所有工作在 `m4-mapreduce` 分支。提交前确认暂存内容里没有密钥（`git diff --cached | grep -E 'sk-|github_pat_'`）。提交信息结尾加 `Co-Authored-By` 行。推送、合并到 main 之前先问用户。
- 用户的全局 gitignore（`~/.config/git/ignore`）排除了 `.claude/settings.local.json`，这是用户的设置，不要改。

---

## 1. 新会话要读的文件（按顺序）

| 顺序 | 文件 | 看什么 |
|---|---|---|
| 1 | `config/DESIGN.md`（v1.4） | 需求、数据模型（第 5 节）、各模块设计、评估口径（第 9 节）、里程碑（第 12 节）、明确不做的事（第 13 节） |
| 2 | `README.md` | 环境变量、所有命令的用法、MCP 客户端配置、C# 对照速查表 |
| 3 | `src/main/resources/application.yml` | 默认配置：并发 4、重试 2、摘要上限 1500 token、temperature 0、文件过滤规则、MCP server 默认关闭 |
| 4 | `src/main/resources/prompts/system.md` | 系统提示（角色、只审新增行、行号规则、严重程度定义、JSON 输出格式） |
| 5 | `src/main/resources/rules/*.md` | 五套审查规则：security / logic / perf / style / naming（相当于原系统的 skill） |
| 6 | `ground_truth.json` | bookmarket PR #1 的标准答案（19 个埋点），格式见 DESIGN 第 9 节 |
| 7 | `src/main/java/com/lee/prreviewer/pipeline/ReviewPipeline.java` | 整条流水线的入口，从这里往下读最快 |
| 按需 | `config/application.yml` | 本地密钥配置。**只确认它存在，不要输出内容** |

---

## 2. 项目是什么

对一个 GitHub PR 做代码审查的 Java 程序（Spring Boot 3.5.16 + Spring AI 1.1.8，JDK 21，Maven）。被审查的目标仓库是用户的 bookmarket（Java / Spring Boot）。

**背景**：用户在西门子用过一套"Agent + skill + PowerShell 脚本"的审查方式——一个 Agent 自己跑脚本拉 diff、读 skill 文件、把整个 PR 放进自己的上下文一次审完。PR 一大，上下文撑满，靠后的文件审得差。

**本项目的做法**：把那套拆开，固定流程写成 Java 代码（Workflow，不是 Agent）：

```
PR 链接 → 拉 PR（GitHub REST）→ 过滤 / 解析 patch / 标新文件行号 → 生成 PR 摘要
  → Map：每个文件单独调用一次 LLM（4 个并发），输入 = 系统提示 + 五套规则 + PR 摘要 + 该文件 diff
  → Reduce：纯代码去重、排序
  → ReviewReport（findings、失败/跳过文件、errors、每次调用的 token 和耗时）
```

- **mapreduce 模式**（主）：每次调用的上下文大小与 PR 规模无关。
- **single 模式**（baseline）：整个 PR 一次调用，相当于原来的 Agent 做法，用于对比实验。
- **评估**：对照人工埋雷的标准答案算召回，重点看"按文件在 PR 中的位置分前 / 中 / 后三段"的召回差异。

**几个容易混淆的概念**（用户问过，回答时保持一致）：
- **DeepSeek 不是 Agent**：它没有工具、不决定流程，每次调用只是"输入一个文件的 diff + 规则，输出 findings JSON"。审哪个文件、怎么汇总全由 Java 代码决定。
- **MCP tools 是给 Agent 用的，不是给 DeepSeek 用的**：`mcp` 模式下 jar 作为 MCP server，由 MCP 客户端（Claude Code、Claude Desktop、VSCode Copilot）作为子进程启动，通过 stdin/stdout 通信。Agent（如 Claude）负责理解用户意图、把请求翻译成 tool 调用、解读返回的报告；中间的审查流水线由 jar 完成，内部调用 DeepSeek。
- **两种用法**：命令行（主要用法，做实验用这个，不需要任何 Agent）；MCP（可选，在聊天里让 Agent 调用）。

---

## 3. 当前状态

| 里程碑 | 内容 | 状态 |
|---|---|---|
| M1 | 骨架、配置、数据模型、LlmClient + 计量 | ✅ |
| M2 | PR 链接解析、GitHub 拉取（分页）、patch 解析、过滤、PR 摘要 | ✅ |
| M3 | 单文件审查、prompt、规则、重试 | ✅ |
| M4 | 并发 map + 去重汇总 + CLI `review` + Markdown 报告 | ✅ |
| M5 | single 模式 + 报告错误溯源（`errors`） | ✅ |
| M6 | 召回评估 + CLI `eval`；类别加 LOGIC / PERF | ✅ |
| M7 | MCP server 四个 tool | ✅ 已登记到 Claude Code 并在真实会话中调用 `review_pr` 验收 |
| M8（可选） | 审查结果写回 PR 评论 | 未开始 |
| 正式实验 | 50+ 文件的埋雷 PR + 答案 | 等用户准备 PR |

- 单元测试 **99 个，全部通过**（`mvn package`）。单测不调用真实 LLM 和 GitHub。
- **git**：分支 `m4-mapreduce`，相对 main 的提交：

| 提交 | 内容 |
|---|---|
| `8e1c0f1` | M4 |
| `a74ae0b` | M5 + 错误溯源 + temperature |
| `b3dc1b2` | M6 + LOGIC/PERF |
| `26db3c9` | M7 |
| `3d84857` | 修正 FINDING_VALIDATION 记录里误导性的空 `file=` |
| `26317f4` | DESIGN 与实现对齐 |

  `main` 只有初始提交 `2995910`（含 M1～M3）。远程 `origin` 已配置，**分支未推送、未合并**。

- 测试用 PR：https://github.com/DavidLee617/bookmarket/pull/1 （分支 `feature/order-payment-coupon-search`，head `7fd23c0`，12 个文件，其中 11 个 `.java`）。

---

## 4. 环境

- **机器**：macOS（Apple Silicon），shell 为 zsh。
- **JDK 21**：`brew install openjdk@21`（21.0.12.1）。在 **`~/.zshenv`** 里设置了 `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home` 并加到 `PATH` 最前。放 `.zshenv` 是因为非交互式 shell（Claude 执行的命令、脚本）不读 `.zshrc`。`java`、`mvn` 不需要手动指定 JDK。机器上另有 JDK 26 和 17，**不要用 26**（超出 Spring Boot 3.5 支持范围，最高 25）。
- **Maven** 3.9.16（Homebrew）。
- **LLM**：DeepSeek，`base-url=https://api.deepseek.com`（不带 `/v1`），`model=deepseek-chat`，temperature 0。
- **配置文件**：
  - `src/main/resources/application.yml`：默认配置，打进 jar，密钥从环境变量 `LLM_BASE_URL / LLM_API_KEY / LLM_MODEL / GITHUB_TOKEN` 读。
  - `config/application.yml`：本地配置（密钥），Spring Boot 自动加载工作目录下的 `./config/application.yml`。缺配置时启动即报错并提示缺哪个。
- **MCP 已登记到 Claude Code**（local 范围，写在 `~/.claude.json` 的本项目下）：

  ```bash
  claude mcp add --scope local pr-reviewer -- /opt/homebrew/opt/openjdk@21/bin/java -jar /Users/lee/Documents/spring/pr-reviewer/target/pr-reviewer.jar mcp --spring.config.additional-location=file:/Users/lee/Documents/spring/pr-reviewer/config/
  ```

  查看：`claude mcp list` / 会话里 `/mcp`；删除：`claude mcp remove pr-reviewer -s local`。重新 `mvn package` 后，已开着的会话仍连旧进程，要开新会话或在 `/mcp` 里重连。

---

## 5. 常用命令（在项目根目录）

```bash
mvn package                                   # 编译 + 99 个单测，产出 target/pr-reviewer.jar
java -jar target/pr-reviewer.jar ping         # 测 LLM 连通（打印 token 和耗时）
java -jar target/pr-reviewer.jar files --pr <PR链接> [--diff]                  # 预处理：文件列表、跳过列表、PR 摘要、带行号的 diff
java -jar target/pr-reviewer.jar review-file --pr <PR链接> --file OrderService.java   # 审单个文件
java -jar target/pr-reviewer.jar review --pr <PR链接> [--mode mapreduce|single] [--out report.json]
java -jar target/pr-reviewer.jar eval --report report.json --truth ground_truth.json
java -jar target/pr-reviewer.jar mcp          # MCP stdio server（由客户端拉起，不要手动在终端跑）
```

- 日志写 stderr，同时写文件 `~/.pr-reviewer/pr-reviewer.log`（`logging.file.name` 可改）；stdout 只给结果（mcp 模式下 stdout 是协议通道）。
- **查看 token 消耗**：运行中看 CLI 进度行（每文件 in/out）或 `tail -f ~/.pr-reviewer/pr-reviewer.log`（MCP 模式只能看这个，含 `review_start` / `file_reviewed [n/N]` / `llm_call` / `review_done`，每行带 pid）；事后看 `--out` 报告里的 `calls`（每次调用一条）和 `totalInputTokens` / `totalOutputTokens`。程序不记录费用和 DeepSeek 前缀缓存命中数（API 有返回，未采集），费用看 DeepSeek 控制台。
- `review` 的 stdout：实时进度 → Markdown 报告 → 汇总。预处理失败或有文件审查失败时退出码 1，报告照常输出。
- `report*.json` 已被 `.gitignore` 排除。

---

## 6. 代码结构

```
src/main/java/com/lee/prreviewer/
├── PrReviewerApplication   入口；mcp 命令时设系统属性打开 MCP server 并常驻，其他命令跑完即退出
├── app/                    入口层：CliRunner、MarkdownReport、EvalReport、McpTools（四个 @Tool）、McpServerConfig
├── config/                 LlmProperties / GitHubProperties / ReviewProperties（@Validated）
├── github/                 PrUrlParser、GitHubPrClient（分页拉全，错误带状态码、URL 和 GitHub message）
├── preprocess/             PatchParser（带新文件行号的 annotatedDiff）、FileFilter、PrSummaryBuilder
├── map/                    FileReviewer（行号范围校验）、JsonRetryingCaller（调用 + JSON 重试 + 生成 ReviewError）、
│                           PromptBuilder、LlmOutputParser、FileReviewResult
├── pipeline/               ReviewPipeline、MapReduceReviewer（固定线程池）、SingleCallReviewer、ReviewProgressListener
├── reduce/                 FindingAggregator
├── llm/                    LlmClient（指数退避重试 + 计量 + 结构化日志）、CallMetrics、LlmResponse、LlmCallException
├── eval/                   RecallEvaluator、GroundTruth、EvalResult
└── model/                  DESIGN 第 5 节的 record / enum（含 ReviewError、ReviewStage）+ PreparedPr
src/main/resources/
├── application.yml
├── logback-spring.xml      日志 → stderr + ~/.pr-reviewer/pr-reviewer.log（prudent 模式，多进程可同时写）
├── prompts/system.md
└── rules/security.md, logic.md, perf.md, style.md, naming.md
```

### 关键实现细节

- **prompt**：system = `system.md` + 五套规则原文（`PromptBuilder` 拼接）；user = PR 摘要 + 本文件 diff。同一 PR 的所有调用前缀相同，DeepSeek 自动命中前缀缓存。直接构造 `SystemMessage` / `UserMessage`，避免 Java 代码里的 `{}` 被 Spring AI 当模板占位符。
- **single 模式**：系统提示与 map 完全相同，"每条 finding 必须填 file"的要求写在 user 消息末尾。LLM 填的 file 先完全匹配，否则接受唯一匹配的路径后缀并补全。整次调用失败时 `failedFiles` = 全部待审文件。
- **重试两层**：`LlmClient` 对 API 错误 / 超时指数退避（1s、2s，最多 2 次；4xx 不重试，429 重试；Spring AI 自带重试已关闭以保证 attempts 计数准确）；`JsonRetryingCaller` 对非法 JSON 带错误信息重调 1 次（label 加后缀 ` [json-retry]`）。map 和 single 共用同一策略。
- **finding 行号范围**：只接受新增行，以及与新增行紧挨着的上下文行（跨过删除行判断相邻）；其余丢弃，记 `FINDING_VALIDATION` 错误。
- **错误溯源 `errors`**：`LlmClient` 记录每次失败尝试；`JsonRetryingCaller` 转成 `ReviewError`，按发生顺序（首次解析失败是否"已恢复"要等重试结果出来再补上位置）；`ReviewError.describe()` 输出异常类型 + 完整 cause 链；LLM_OUTPUT 附 LLM 输出开头 300 字；预处理失败不抛异常，返回只含一条 PREPARE 错误的报告。
- **map 并发**：每次 review 新建 `review.concurrency` 大小的线程池；意外异常在任务内兜住记为 INTERNAL；结果按输入顺序返回；进度回调串行、按完成顺序编号。
- **去重**：键 `file + line + category`，保留 severity 最高；排序 severity → file → line。
- **eval 匹配**：候选 = 同文件且 `line ∈ [lineStart-3, lineEnd+3]`；所有候选按 类别一致 → 行距 → 埋点顺序 → finding 顺序 排序后贪心配对，一条 finding 只用一次。分段按主位置（`locations[0]`）文件在报告 `prFiles` 中的下标三等分。"审查范围内" = 至少一个位置的文件没被过滤跳过。读取旧报告时缺失的列表字段按空处理。
- **MCP**：`spring.ai.mcp.server.enabled` 默认 false，`PrReviewerApplication` 只在 `mcp` 命令下设为 true；tools 注册成 `SyncToolSpecification` bean（**不能**注册成 `ToolCallbackProvider`，否则被 chat 模型收集为 LLM 工具，形成循环依赖、启动失败）；mcp 模式下 `CliRunner` 不向 stdout 写任何东西；MCP Java SDK 0.18.3 的 stdio 只支持协议版本 `2024-11-05`，客户端会自动协商。

---

## 7. 做过的决定及原因

| 决定 | 原因 |
|---|---|
| 被审查语言由 C# 改为 Java | 目标仓库 bookmarket 是 Java |
| 只审 `**/*.java`，排除 `target/ build/ generated/ generated-sources/` | 规则都针对 Java；配置和文档每个文件都要花一次调用却审不出有意义的问题 |
| PR 摘要上限 500 → 1500 token | 50+ 文件的 PR（dotnet/eShop#1002）上，500 会截掉全部签名和大部分文件名 |
| 保留 PR 摘要 | "每次把完整 diff 给 LLM"会让成本按"文件数 × PR 大小"增长、单次输入与 PR 规模挂钩、重复报问题；摘要是固定小篇幅的跨文件上下文 |
| Spring Boot 3.5.16 + Spring AI 1.1.8 | 设计要求 Boot 3；Spring AI 2.x 需要 Boot 4 |
| 关闭 Spring AI 自带重试 | 默认重试 10 次，会让 attempts 计数不准 |
| 所有日志写 stderr、关 banner | CLI 的 stdout 只留给结果；MCP stdio 模式下 stdout 是协议通道 |
| 规则文件只写通用规则，**不能参照被审查 PR 的内容** | 否则评估不可信（第一版 security.md 不慎写进了 PR 里的场景，已改掉） |
| 类别加 LOGIC、PERF（五类） | 埋雷文档 19 条里 11 条 logic、1 条 perf；三类时逻辑 bug 全归 STYLE，类别一致率无意义 |
| temperature 固定 0（`llm.temperature`） | 不设时同一 PR 两次结果条数不同，召回对比无意义 |
| 报告加 `errors`（ReviewError / ReviewStage） | 用户要求：任何一步出问题都要能从报告溯源到环节、文件、具体调用，包括被重试恢复的 |
| 报告加 `headSha`、`prFiles` | 分段需要 PR 原始文件顺序；headSha 用来核对答案行号是否针对同一提交 |
| eval：±3 行 + **一对一匹配**，类别优先于行距 | ±3 按用户埋雷文档口径。只看位置时一条 finding 会"命中"多个相邻埋点，PR #1 上召回被高估（mapreduce 18→17、single 17→14）；行距优先时"返回 User 实体"错配到相邻行的 System.out。副作用：类别一致率偏乐观 |
| `application.yml` 硬编码 token 的埋点（B03）保留在答案里 | 文件被过滤规则跳过，任何模式都命中不了；召回分"全部"和"审查范围内"两个口径，如实反映盲区 |
| 标准答案格式扩展（`locations` 多位置、`categories` 多类别、`severity`、`crossFile`） | 跨文件雷涉及两个位置；可分别统计单文件 / 跨文件召回 |
| MCP `review_file` 返回完整 `FileReviewResult`（设计原为 `List<Finding>`） | 带 calls / error / errors，失败原因可溯源 |
| MCP `list_pr_files` 不返回 diff 正文 | 避免把整个 PR 塞进 Agent 上下文 |
| 日志同时写文件 `~/.pr-reviewer/pr-reviewer.log`，流水线加 `review_start` / `file_reviewed` / `review_done` 进度日志 | MCP 模式下 jar 由客户端后台拉起，stderr 和 CLI 进度都看不到（Claude Code 的 MCP 日志只记录启动阶段的 stderr）；DESIGN 7.2 允许日志写文件 |

---

## 8. 实测数据（PR #1，DeepSeek）

**两种模式对比**（五类规则，temperature 0）：

| | mapreduce | single |
|---|---|---|
| LLM 调用 | 11 | 1 |
| 输入 / 输出 token | 29067 / 2201 | 6418 / 1622 |
| 墙钟 | 约 9s | 约 8.5s |
| findings | 25 | 18 |
| **召回（全部）** | **17 / 19（89.5%）** | **14 / 19（73.7%）** |
| 审查范围内 | 17 / 18 | 14 / 18 |
| 单文件雷 / 跨文件雷 | 15/16 · 2/3 | 12/16 · 2/3 |
| HIGH / MEDIUM / LOW | 6/8 · 9/9 · 2/2 | 6/8 · 8/9 · 0/2 |
| 类别一致率 | 17/17 | 14/14 |

- 两种模式都没找到：B03（application.yml，不在审查范围）、B11（跨文件：`int` 改 `Integer` 且 `@Min` 对 null 放行 → 拆箱 NPE）。
- single 额外漏掉 B15（魔法值）、B16（命名）、B19（pay 用请求体 userId，IDOR）；single 一条 LOW 都没报。
- single 输入 token 只有 mapreduce 的约 1/4：mapreduce 每次调用重复约 2000+ token 的系统提示和规则。
- **分段对比在 PR #1 上没有意义**：19 个埋点有 14 个在后段、中段只有 1 个。
- temperature 0 下同一模式跑两次：single 完全一致；mapreduce 26 条中 24 条（位置 + 类别）一致，差异都是 LOW/MEDIUM。DeepSeek 在 temperature 0 下也不完全确定。
- 不存在的 PR（#9999）：报告和 JSON 照常输出，`errors` 一条 PREPARE（含 HTTP 404 和具体 GitHub 接口地址），退出码 1。
- MCP：自写 stdio 客户端验证了握手、四个 tool、错误返回（`isError=true` + 原始信息）、stdout 只有 JSON、stdin 关闭后进程退出；模拟 GUI 客户端（cwd=/tmp、无 JAVA_HOME）加 `--spring.config.additional-location` 能读到密钥；用户在 Claude Code 新会话中调用 `review_pr` 成功。

---

## 9. 待办 / 待决定

| # | 事项 | 说明 |
|---|---|---|
| 1 | **准备正式实验的 PR**（用户） | 50+ 文件，埋点分散在 PR 文件列表的前 / 中 / 后三段。答案由用户写成文档（参考 PR #1 的埋雷文档格式：文件:行、类型、严重、是否跨文件、问题描述），Claude 转成 `ground_truth.json` 并逐条核对行号与 diff。**答案不能由 AI 生成** |
| 2 | 改动行"相邻"范围是否放宽 | 现在只接受紧挨着新增行的 1 行上下文。MCP 验收时 `OrderController:65`（cancel 接口越权，PR 之前就存在的问题）被丢弃；LLM 有时报 64 行（保留）、有时报 65 行（丢弃）。放宽会放进更多与改动无关的老问题。等大 PR 数据再定 |
| 3 | 严重程度偏高 | HIGH 里有 double 算金额、全表 findAll 等，答案里是 MEDIUM。可在 `system.md` 加通用定级指引。召回不看严重程度，优先级低 |
| 4 | 跨文件同一问题不合并 | 如 `OrderController:71` 与 `OrderService:142` 是同一个 batchCancel 越权。按设计不合并（需 reduce 阶段调用 LLM，DESIGN 列为 v2）；对召回无影响 |
| 5 | 是否加"mapreduce 不带摘要"对照组 | 量化摘要的价值，属设计之外的实验；PR #1 跨文件样本太少，等大 PR 再定 |
| 6 | 分支合并 / 推送 | M4～M7 都在 `m4-mapreduce`，未推送、未合并；先问用户 |
| 7 | 更换泄露过的密钥 | DeepSeek key 和 GitHub token 在对话里明文出现过，建议用户在各自控制台换新，直接改 `config/application.yml` |
| 8 | Mockito 自动挂载警告 | 测试时打印 "Mockito is currently self-attaching"，不影响结果；未来 JDK 需在 surefire 配 `-javaagent` |

只有单测覆盖、真实运行中还没触发过的路径：LLM API 重试、JSON 重试、single 超出上下文失败、GitHub 分页（需 100+ 文件）。不用专门测，跑大 PR 时遇到会记进 `errors`。

---

## 10. 下一步

1. **正式实验**（用户找到 PR 后）：转写并核对答案 → 两种模式各跑 2～3 次 `review --out` → 对每份报告 `eval` → 重点看分段召回、跨文件召回、single 是否因上下文装不下而失败。
2. **M8（可选）**：审查结果写回 PR 评论（DESIGN 第 12 节）——`POST /repos/{owner}/{repo}/pulls/{n}/reviews` 一次提交一个 review；每条评论需 `path`、`line`、`side: RIGHT`、head `commit_id`；不在 diff 范围内的 finding 汇总进 review 正文；CLI 加 `--post-comments`，默认关闭。
