# pr-reviewer 开发记录

> 记录截至 2026-10-02 的全部进展、决定和待办，用于下次接着做。
> 设计文档：[config/DESIGN.md](config/DESIGN.md)（v1.2）｜使用说明：[README.md](README.md)

---

## 1. 当前状态一览

| 里程碑 | 内容 | 状态 |
|---|---|---|
| M1 | 骨架、配置、数据模型、LlmClient + 计量 | ✅ 已完成，真实调通 DeepSeek |
| M2 | PR 链接解析、GitHub 拉取、patch 解析、过滤、PR 摘要 | ✅ 已完成，用 PR #1 和 dotnet/eShop#1002 验证 |
| M3 | 单文件审查、prompt、三套规则、重试 | ✅ 已完成，PR #1 的 11 个文件全部一次输出合法 JSON |
| **M4** | **并发 map + 去重汇总 + CLI `review` + 报告** | ⏭ **下一步** |
| M5 | single 模式（baseline） | 未开始 |
| M6 | 召回评估 + CLI `eval` | 未开始 |
| M7 | MCP server 四个 tool | 未开始 |
| M8 | 写回 PR 评论（可选） | 未开始 |

- 单元测试：68 个，全部通过（`mvn package`）
- 测试用 PR：https://github.com/DavidLee617/bookmarket/pull/1 （分支 `feature/order-payment-coupon-search`，12 个文件，11 个 .java）

---

## 2. 做过的决定（及原因）

| 决定 | 原因 |
|---|---|
| **被审查语言由 C# 改为 Java** | 实际要审的仓库 bookmarket 是 Java（Spring Boot）。DESIGN.md 已同步为 v1.2 |
| 只审 `**/*.java`，排除 `target/`、`build/`、`generated/`、`generated-sources/` | 三套规则都针对 Java；配置、文档每个都要花一次 LLM 调用却审不出有意义的问题 |
| PR 摘要上限 500 → **1500** token | 在 50+ 文件的 PR（eShop#1002）上，500 会把全部签名和大部分文件名截掉 |
| JDK 21（`D:\Application\jdk21`） | 设计要求 21；JDK 26 超出 Spring Boot 3.5 支持范围（最高 25） |
| Spring Boot **3.5.16** + Spring AI **1.1.8** | 设计要求 Boot 3；2026-10-02 查 Maven Central 的最新稳定版。Spring AI 2.x 需要 Boot 4 |
| LLM 用 DeepSeek（`deepseek-chat`） | 用户指定 |
| 本地密钥放 `config/application.yml` | Spring Boot 自动加载工作目录下的 `./config/application.yml`；已加入 `.gitignore`，不会打进 jar |
| 关闭 Spring AI 自带重试，由 LlmClient 自己重试 | Spring AI 默认重试 10 次，会让 `attempts` 计数不准 |
| 所有日志写 stderr、关闭 banner | CLI 的 stdout 只留给结果；MCP stdio 模式下 stdout 是协议通道 |
| 规则文件不能参照 PR 内容来写 | 第一版 security.md 不小心写进了 PR 里的场景（请求头固定口令、重复支付、优惠复用），已改成通用写法，否则评估不可信 |
| 摘要：v1 保留 | 讨论过"为什么不每次把完整 diff 给 LLM"：成本按"文件数 × PR 大小"增长、单次输入又与 PR 规模挂钩、会重复报问题。摘要是固定小篇幅的跨文件上下文 |

### 设计之外额外加的东西

- CLI 命令 `ping`（M1 验收用）、`files`（≈ MCP 的 `list_pr_files`）、`review-file`（≈ MCP 的 `review_file`）
- `ReviewPipeline.prepare()` / `reviewFile()`：CLI 和后续 MCP 共用
- 解析 patch 时去掉 CRLF 的 `\r` 和文件开头的 UTF-8 BOM
- `FileFilter` 的 include 规则（`review.filter.include-globs`）

---

## 3. 环境与配置

### 本机环境
- JDK 21：用户级 `JAVA_HOME = D:\Application\jdk21`；**系统级 JAVA_HOME 仍是 JDK 17**。VSCode 需重启后终端才会用 21。验证：`java -version`、`mvn -v`
- Maven 3.9.9（`C:\tools\apache-maven-3.9.9`）
- 控制台中文 / ✓ 乱码：PowerShell 里先执行 `chcp 65001`
- VSCode 里出现 "non-project file" 警告：用"文件 → 打开文件夹"直接打开 `pr-reviewer` 目录

### 配置文件
- `src/main/resources/application.yml`：默认配置（打进 jar），密钥全部从环境变量读
- `config/application.yml`：本地配置，写了 `llm.base-url / api-key / model` 和 `github.token`（**不提交**）
- 缺少 `LLM_*` 或 `GITHUB_TOKEN` 时启动直接报错退出，并提示缺的是哪个

> ⚠ DeepSeek key 和 GitHub token 在对话里明文出现过，建议之后在各自控制台换一个新的，再更新 `config/application.yml`。

---

## 4. 怎么接着用

在 `pr-reviewer` 目录下执行：

```powershell
mvn package                                                         # 编译 + 68 个单元测试
java -jar target/pr-reviewer.jar ping                               # 测 LLM 连通
java -jar target/pr-reviewer.jar files --pr https://github.com/DavidLee617/bookmarket/pull/1 [--diff]
java -jar target/pr-reviewer.jar review-file --pr https://github.com/DavidLee617/bookmarket/pull/1 --file OrderService.java
```

继续开发时，对 Claude 说"继续做 M4"即可；建议先让它读一下本文件和 `config/DESIGN.md`。

---

## 5. 代码结构（已实现部分）

```
src/main/java/com/lee/prreviewer/
├── PrReviewerApplication.java   入口；非 mcp 命令跑完即退出
├── app/CliRunner.java           命令：ping / files / review-file（review、eval、mcp 待实现）
├── config/                      LlmProperties / GitHubProperties / ReviewProperties（@Validated，缺配置启动失败）
├── github/                      PrUrlParser、GitHubPrClient（分页拉全、错误带状态码和 message）、PrFile、PrInfo、GitHubApiException
├── preprocess/                  PatchParser（带行号的 annotatedDiff）、FileFilter、PrSummaryBuilder（Java public 签名启发式 + 截断）
├── map/                         FileReviewer（JSON 重试、行号范围过滤）、PromptBuilder、LlmOutputParser、FileReviewResult
├── pipeline/ReviewPipeline.java prepare()、reviewFile()
├── llm/                         LlmClient（指数退避重试 + 计量 + 结构化日志）、CallMetrics、LlmResponse、LlmCallException
└── model/                       设计第 5 节的全部 record / enum，外加 PreparedPr
src/main/resources/
├── prompts/system.md            系统提示（Java 审查员、行号规则、严重程度、JSON 格式）
└── rules/style.md, security.md, naming.md
```

代码注释里的「C# 对照」说明 Java/Spring 概念在 C#/.NET 中的对应写法（record、IOptions、HttpClient、xUnit、Moq 等），README 末尾有汇总表。

### 关键实现细节
- **重试分两层**：API 错误 / 超时由 `LlmClient` 指数退避（1s、2s，最多 `review.max-retries` = 2 次；4xx 不重试，429 重试）；输出不是合法 JSON 由 `FileReviewer` 带错误信息再调 1 次（label 后缀 ` [json-retry]`）
- **finding 行号范围**：只接受新增行，以及与新增行相邻的上下文行（跳过中间的删除行判断相邻）；其余丢弃并写 `finding_dropped` 警告日志
- **prompt 结构**：system = 系统提示 + 三套规则；user = PR 摘要 + 本文件 diff。同一 PR 的所有调用前缀相同，DeepSeek 会自动命中前缀缓存
- **摘要截断**：按约 3 字符/token 估算，超限先删签名再删文件，列表末尾写"已截断"
- **构造 Prompt 时直接用 SystemMessage / UserMessage**：避免 Java 代码里的 `{}` 被 Spring AI 当成模板占位符

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

---

## 7. 待决定 / 待办

| # | 事项 | 说明 |
|---|---|---|
| 1 | 是否增加 `LOGIC` 类别 | 逻辑 bug（库存 `<=`、分页偏移）现在被归到 STYLE。召回不受影响，类别一致率会偏低 |
| 2 | 是否加"mapreduce 不带摘要"对照组 | 用来量化摘要的价值；属于设计之外的实验，M6 时再定 |
| 3 | 准备 `ground_truth.json` | 人工维护（设计第 11 节），不能由同一模型生成。实验目标 PR 需 50+ 文件，埋点分布在前 / 中 / 后三段 |
| 4 | 更换泄露过的密钥 | 见第 3 节 |
| 5 | Mockito 自动挂载警告 | 测试时打印 "Mockito is currently self-attaching"，不影响结果；未来 JDK 版本需在 surefire 里配置 `-javaagent` |

---

## 8. M4 要做什么（下一步）

按 DESIGN.md 第 6.8、7.1、12 节：

1. `MapReduceReviewer`：固定大小线程池（`review.concurrency` = 4）并行调用 `FileReviewer`；单个文件失败记入 `failedFiles`，不影响其他文件
2. `FindingAggregator`（纯代码）：按 `file + line + category` 去重，保留 severity 最高的；排序 severity（HIGH → LOW）→ file → line
3. `ReviewPipeline.review(request, mode)`，产出 `ReviewReport`（墙钟耗时、总 token、全部 CallMetrics）
4. CLI `review --pr <链接> --mode mapreduce [--out report.json]`：
   - 实时进度：`[3/11] service/OrderService.java  in=3242 out=909  4.5s  ✓`
   - 结束汇总：按严重程度的 finding 数、失败 / 跳过文件数、总 token、总耗时
   - 输出可读的 Markdown 报告；`--out` 时写完整 JSON
5. 单测：`FindingAggregator` 去重保留最高 severity、排序正确
6. 验收：mapreduce 模式跑通 PR #1
