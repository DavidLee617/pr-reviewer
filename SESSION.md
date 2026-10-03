# pr-reviewer 开发记录

> 记录截至 2026-10-02 的全部进展、决定和待办，用于下次接着做。
> 设计文档：[config/DESIGN.md](config/DESIGN.md)（v1.3）｜使用说明：[README.md](README.md)

---

## 1. 当前状态一览

| 里程碑 | 内容 | 状态 |
|---|---|---|
| M1 | 骨架、配置、数据模型、LlmClient + 计量 | ✅ 已完成，真实调通 DeepSeek |
| M2 | PR 链接解析、GitHub 拉取、patch 解析、过滤、PR 摘要 | ✅ 已完成，用 PR #1 和 dotnet/eShop#1002 验证 |
| M3 | 单文件审查、prompt、三套规则、重试 | ✅ 已完成，PR #1 的 11 个文件全部一次输出合法 JSON |
| M4 | 并发 map + 去重汇总 + CLI `review` + 报告 | ✅ 已完成，PR #1 跑通（见第 6 节） |
| M5 | single 模式（baseline）+ 报告错误溯源 | ✅ 已完成，PR #1 跑通（见第 6 节） |
| **M6** | **召回评估 + CLI `eval`** | ⏭ **下一步** |
| M7 | MCP server 四个 tool | 未开始 |
| M8 | 写回 PR 评论（可选） | 未开始 |

- 单元测试：86 个，全部通过（`mvn package`）
- 测试用 PR：https://github.com/DavidLee617/bookmarket/pull/1 （分支 `feature/order-payment-coupon-search`，12 个文件，11 个 .java）

---

## 2. 做过的决定（及原因）

| 决定 | 原因 |
|---|---|
| **被审查语言由 C# 改为 Java** | 实际要审的仓库 bookmarket 是 Java（Spring Boot）。DESIGN.md 已同步为 v1.2 |
| 只审 `**/*.java`，排除 `target/`、`build/`、`generated/`、`generated-sources/` | 三套规则都针对 Java；配置、文档每个都要花一次 LLM 调用却审不出有意义的问题 |
| PR 摘要上限 500 → **1500** token | 在 50+ 文件的 PR（eShop#1002）上，500 会把全部签名和大部分文件名截掉 |
| JDK 21 | 设计要求 21；JDK 26 超出 Spring Boot 3.5 支持范围（最高 25）。换到 Mac 后机器默认是 26，已另装 21（见第 3 节） |
| Spring Boot **3.5.16** + Spring AI **1.1.8** | 设计要求 Boot 3；2026-10-02 查 Maven Central 的最新稳定版。Spring AI 2.x 需要 Boot 4 |
| LLM 用 DeepSeek（`deepseek-chat`） | 用户指定 |
| 本地密钥放 `config/application.yml` | Spring Boot 自动加载工作目录下的 `./config/application.yml`；已加入 `.gitignore`，不会打进 jar |
| 关闭 Spring AI 自带重试，由 LlmClient 自己重试 | Spring AI 默认重试 10 次，会让 `attempts` 计数不准 |
| 所有日志写 stderr、关闭 banner | CLI 的 stdout 只留给结果；MCP stdio 模式下 stdout 是协议通道 |
| 规则文件不能参照 PR 内容来写 | 第一版 security.md 不小心写进了 PR 里的场景（请求头固定口令、重复支付、优惠复用），已改成通用写法，否则评估不可信 |
| 报告新增 `errors`（DESIGN v1.3） | 用户要求：任何一步出问题都要能从报告溯源到环节、文件、具体调用。含已被重试恢复的问题；预处理失败也输出报告 |
| temperature 固定为 0（`llm.temperature`） | 未设置时同一 PR 两次结果条数不同，召回对比没有意义 |
| single 整次失败时 `failedFiles` = 全部待审文件 | 一次调用失败等于所有文件都没审到 |
| single 的系统提示与 map 完全相同，"填 file"的要求写在 user 消息末尾 | 两种模式只差"一次给多少 diff"，对比才公平；也能命中前缀缓存 |
| 摘要：v1 保留 | 讨论过"为什么不每次把完整 diff 给 LLM"：成本按"文件数 × PR 大小"增长、单次输入又与 PR 规模挂钩、会重复报问题。摘要是固定小篇幅的跨文件上下文 |

### 设计之外额外加的东西

- CLI 命令 `ping`（M1 验收用）、`files`（≈ MCP 的 `list_pr_files`）、`review-file`（≈ MCP 的 `review_file`）
- `ReviewPipeline.prepare()` / `reviewFile()`：CLI 和后续 MCP 共用
- 解析 patch 时去掉 CRLF 的 `\r` 和文件开头的 UTF-8 BOM
- `FileFilter` 的 include 规则（`review.filter.include-globs`）
- `review` 的 `--mode` 默认 `mapreduce`；进度行省略所有文件共同的目录前缀；有文件失败时退出码为 1（报告照常输出）
- `ReviewProgressListener`：进度回调（CLI 打印进度用，MCP 可不传）

---

## 3. 环境与配置

### 本机环境（Mac，2026-10-02 起）
- JDK 21：`brew install openjdk@21`（21.0.12.1）。**`~/.zshenv`** 里设置了 `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home` 并放到 `PATH` 最前。放 `.zshenv` 而不是 `.zshrc`，是因为非交互式 shell（Claude 执行的命令、脚本、IDE 任务）不读 `.zshrc`。机器上另有 JDK 26（`/Library/Java/JavaVirtualMachines/jdk-26.jdk`）和 17，不要用
- Maven 3.9.16（Homebrew）
- 不需要手动指定 JDK。验证：`java -version`、`mvn -v` 应显示 21.0.12.1

### 之前的 Windows 环境（备查）
- JDK 21 在 `D:\Application\jdk21`（用户级 JAVA_HOME；系统级仍是 17）；Maven 在 `C:\tools\apache-maven-3.9.9`
- 控制台中文 / ✓ 乱码：PowerShell 里先执行 `chcp 65001`
- VSCode 里出现 "non-project file" 警告：用"文件 → 打开文件夹"直接打开 `pr-reviewer` 目录

### 配置文件
- `src/main/resources/application.yml`：默认配置（打进 jar），密钥全部从环境变量读
- `config/application.yml`：本地配置，写了 `llm.base-url / api-key / model` 和 `github.token`（**不提交**）
- 缺少 `LLM_*` 或 `GITHUB_TOKEN` 时启动直接报错退出，并提示缺的是哪个

> ⚠ DeepSeek key 和 GitHub token 在对话里明文出现过（Mac 上配置时又贴过一次新的），建议之后在各自控制台再换一个，**直接改 `config/application.yml`，不要贴进对话**。

---

## 4. 怎么接着用

在 `pr-reviewer` 目录下执行：

```bash
mvn package                                                         # 编译 + 86 个单元测试
java -jar target/pr-reviewer.jar ping                               # 测 LLM 连通
java -jar target/pr-reviewer.jar files --pr https://github.com/DavidLee617/bookmarket/pull/1 [--diff]
java -jar target/pr-reviewer.jar review-file --pr https://github.com/DavidLee617/bookmarket/pull/1 --file OrderService.java
java -jar target/pr-reviewer.jar review --pr https://github.com/DavidLee617/bookmarket/pull/1 --out report.json
java -jar target/pr-reviewer.jar review --pr https://github.com/DavidLee617/bookmarket/pull/1 --mode single --out report-single.json
```

继续开发时，对 Claude 说"继续做 M6"即可；建议先让它读一下本文件和 `config/DESIGN.md`。

---

## 5. 代码结构（已实现部分）

```
src/main/java/com/lee/prreviewer/
├── PrReviewerApplication.java   入口；非 mcp 命令跑完即退出
├── app/CliRunner.java           命令：ping / files / review-file / review（eval、mcp 待实现）
├── app/MarkdownReport.java      ReviewReport → Markdown（按 severity 分节）
├── config/                      LlmProperties / GitHubProperties / ReviewProperties（@Validated，缺配置启动失败）
├── github/                      PrUrlParser、GitHubPrClient（分页拉全、错误带状态码和 message）、PrFile、PrInfo、GitHubApiException
├── preprocess/                  PatchParser（带行号的 annotatedDiff）、FileFilter、PrSummaryBuilder（Java public 签名启发式 + 截断）
├── map/                         FileReviewer（行号范围过滤）、JsonRetryingCaller（调用 + JSON 重试 + 生成 ReviewError，map/single 共用）、PromptBuilder、LlmOutputParser、FileReviewResult
├── pipeline/                    ReviewPipeline（review / prepare / reviewFile）、MapReduceReviewer（固定线程池）、SingleCallReviewer（baseline）、ReviewProgressListener
├── reduce/FindingAggregator     按 file + line + category 去重保留最高 severity；排序 severity → file → line
├── llm/                         LlmClient（指数退避重试 + 计量 + 结构化日志）、CallMetrics、LlmResponse、LlmCallException
└── model/                       设计第 5 节的全部 record / enum（含 v1.3 的 ReviewError、ReviewStage），外加 PreparedPr
src/main/resources/
├── prompts/system.md            系统提示（Java 审查员、行号规则、严重程度、JSON 格式）
└── rules/style.md, security.md, naming.md
```

代码注释里的「C# 对照」说明 Java/Spring 概念在 C#/.NET 中的对应写法（record、IOptions、HttpClient、xUnit、Moq 等），README 末尾有汇总表。

### 关键实现细节
- **重试分两层**：API 错误 / 超时由 `LlmClient` 指数退避（1s、2s，最多 `review.max-retries` = 2 次；4xx 不重试，429 重试）；输出不是合法 JSON 由 `JsonRetryingCaller` 带错误信息再调 1 次（label 后缀 ` [json-retry]`）
- **finding 行号范围**：只接受新增行，以及与新增行相邻的上下文行（跳过中间的删除行判断相邻）；其余丢弃，写 `finding_dropped` 警告日志并记一条 `FINDING_VALIDATION` 错误
- **错误溯源**：`LlmClient` 把每次失败尝试的错误放进 `LlmResponse.attemptErrors` / `LlmCallException.attemptErrors`；`JsonRetryingCaller` 转成 `ReviewError`（按发生顺序，首次解析失败的"是否已恢复"等重试结果出来后补上）；`ReviewError.describe()` 输出异常类型 + 完整 cause 链
- **single 模式**：LLM 填的 `file` 先完全匹配，否则接受唯一匹配的路径后缀并补全；进度只有一行 `[1/1] single（N 个文件）`
- **prompt 结构**：system = 系统提示 + 三套规则；user = PR 摘要 + 本文件 diff。同一 PR 的所有调用前缀相同，DeepSeek 会自动命中前缀缓存
- **摘要截断**：按约 3 字符/token 估算，超限先删签名再删文件，列表末尾写"已截断"
- **构造 Prompt 时直接用 SystemMessage / UserMessage**：避免 Java 代码里的 `{}` 被 Spring AI 当成模板占位符
- **map 并发**：每次 review 新建 `review.concurrency` 大小的线程池（try-with-resources 关闭）；`FileReviewer` 之外的意外异常在任务内兜住，记为该文件失败；结果按输入顺序返回，进度回调串行、按完成顺序编号
- **totalLatencyMs**：整个 review 的墙钟时间，包括拉取 GitHub 和预处理

---

## 6. M3 实测结果（PR #1，DeepSeek）

| 文件 | in / out token | 耗时 | 问题数 |
|---|---|---|---|
| OrderService.java | 3242 / 909 | 4.5s | 9 |
| OrderController.java | 2833 / 423 | 2.6s | 5 |
| AdminController.java | 2415 / 290 | 2.0s | 4 |
| GlobalExceptionHandler.java | 2263 / 217 | 1.8s | 3 |
| BookService.java | 2316 / 183 | 1.7s | 2 |
| 其余 6 个 | 约 2000 / 6 | 约 1s | 0 |

合计 23 条，全部首次即为合法 JSON，没有行号越界被丢弃的。
（是否命中埋点要到 M6 对照 `ground_truth.json` 才知道。）

### M4 实测（PR #1，mapreduce，并发 4，JDK 21）

- 11 个文件全部成功，失败 0，跳过 1（`application.yml`，不在 include 范围）
- 去重排序后 23 条：HIGH 7 / MEDIUM 14 / LOW 2
- 11 次调用，in=25492 out=2107；**墙钟 9.5s**（各调用耗时之和约 16s）
- 没有 JSON 重试，没有行号越界被丢弃
- 库存 `<=` 又被归到 STYLE（待决定 #1）；`OrderService.java:142` 有 SECURITY + STYLE 两条，类别不同按设计不去重

### M5 实测（PR #1，同一个 PR 两种模式）

| | mapreduce | single |
|---|---|---|
| LLM 调用 | 11 | 1 |
| 输入 token | 25492 | **6093** |
| 输出 token | 2107 | 1887 |
| 墙钟 | 9.5s | 9.7s |
| findings | 23（7 / 14 / 2） | 21（10 / 9 / 2） |

- single 输入只有约 1/4：mapreduce 每次调用都重复约 2000 token 的系统提示 + 规则（×11）。PR #1 太小，看不出"靠后文件审得更差"，要靠 M6 的 50+ 文件 PR
- single 中 LLM 填的 file 全部有效，没有被丢弃的 finding
- 加上 `errors` 后再跑一遍：mapreduce 24 条、single 22 条，两次都没有任何错误。**同一 PR 两次结果数量不同**（见待办 #6）
- 不存在的 PR（#9999）：报告和 JSON 照常输出，`errors` 里一条 `PREPARE`，含 HTTP 404 和具体 GitHub 接口地址，退出码 1

---

## 7. 待决定 / 待办

| # | 事项 | 说明 |
|---|---|---|
| 1 | 是否增加 `LOGIC` 类别 | 逻辑 bug（库存 `<=`、分页偏移）现在被归到 STYLE。召回不受影响，类别一致率会偏低 |
| 2 | 是否加"mapreduce 不带摘要"对照组 | 用来量化摘要的价值；属于设计之外的实验，M6 时再定 |
| 3 | 准备 `ground_truth.json` | 人工维护（设计第 11 节），不能由同一模型生成。实验目标 PR 需 50+ 文件，埋点分布在前 / 中 / 后三段 |
| 4 | 更换泄露过的密钥 | 见第 3 节 |
| 5 | Mockito 自动挂载警告 | 测试时打印 "Mockito is currently self-attaching"，不影响结果；未来 JDK 版本需在 surefire 里配置 `-javaagent` |
| 6 | ~~是否固定 temperature~~ 已固定为 0 | 新配置 `llm.temperature`（默认 0）。PR #1 各跑两次：single 23 条完全一致；mapreduce 26 条中 24 条（位置 + 类别）一致，差异都是 LOW/MEDIUM。DeepSeek 在 temperature=0 下也不完全确定，M6 若要更稳可每种模式跑 2–3 次看命中是否一致 |

---

## 8. M6 要做什么（下一步）

按 DESIGN.md 第 9、12 节：

1. `eval/RecallEvaluator`：读 `ReviewReport` JSON + `ground_truth.json`（`id / file / lineStart / lineEnd / category / note`）
2. 命中规则：存在 finding 的 `file` 相同且 `line` ∈ `[lineStart - 2, lineEnd + 2]`；category 不要求一致，单独统计一致率
3. 输出：总召回、每个埋点的命中情况、**按文件在 PR 文件列表中的位置分前 / 中 / 后三段**的召回、未匹配任何埋点的 finding 数
   - 分段需要 PR 文件顺序：报告里目前没有，要定是从 GitHub 重新拉，还是在 `ReviewReport` 里加文件列表
4. CLI `eval --report report.json --truth ground_truth.json`
5. 单测：±2 行边界
6. 前置：`ground_truth.json` 需人工准备（待办 #3）
7. 验收：两种模式都能算出召回和分段召回
