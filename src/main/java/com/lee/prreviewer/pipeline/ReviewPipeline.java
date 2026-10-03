package com.lee.prreviewer.pipeline;

import com.lee.prreviewer.github.GitHubPrClient;
import com.lee.prreviewer.github.PrFile;
import com.lee.prreviewer.github.PrInfo;
import com.lee.prreviewer.github.PrUrlParser;
import com.lee.prreviewer.llm.CallMetrics;
import com.lee.prreviewer.map.FileReviewResult;
import com.lee.prreviewer.map.FileReviewer;
import com.lee.prreviewer.model.ChangeType;
import com.lee.prreviewer.model.FileDiff;
import com.lee.prreviewer.model.Finding;
import com.lee.prreviewer.model.PrSummary;
import com.lee.prreviewer.model.PreparedPr;
import com.lee.prreviewer.model.ReviewError;
import com.lee.prreviewer.model.ReviewMode;
import com.lee.prreviewer.model.ReviewReport;
import com.lee.prreviewer.model.ReviewStage;
import com.lee.prreviewer.model.ReviewRequest;
import com.lee.prreviewer.model.SkippedFile;
import com.lee.prreviewer.preprocess.FileFilter;
import com.lee.prreviewer.preprocess.PatchParser;
import com.lee.prreviewer.preprocess.PrSummaryBuilder;
import com.lee.prreviewer.reduce.FindingAggregator;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 审查流水线对外统一入口，CLI 和 MCP 都只调用这里。
 * review()（完整审查）、prepare()（预处理）、reviewFile()（单文件 map）。
 * <p>
 * C# 对照：{@code @Service} 与 {@code @Component} 等价，只是语义上标明这是业务服务层（≈ 注册为 Scoped/Singleton 的 XxxService）。
 */
@Service
public class ReviewPipeline {

    private static final Logger log = LoggerFactory.getLogger(ReviewPipeline.class);

    private final GitHubPrClient gitHub;
    private final PatchParser patchParser;
    private final FileFilter fileFilter;
    private final PrSummaryBuilder summaryBuilder;
    private final FileReviewer fileReviewer;
    private final MapReduceReviewer mapReduce;
    private final SingleCallReviewer singleCall;
    private final FindingAggregator aggregator;

    public ReviewPipeline(GitHubPrClient gitHub, PatchParser patchParser, FileFilter fileFilter,
                          PrSummaryBuilder summaryBuilder, FileReviewer fileReviewer,
                          MapReduceReviewer mapReduce, SingleCallReviewer singleCall, FindingAggregator aggregator) {
        this.gitHub = gitHub;
        this.patchParser = patchParser;
        this.fileFilter = fileFilter;
        this.summaryBuilder = summaryBuilder;
        this.fileReviewer = fileReviewer;
        this.mapReduce = mapReduce;
        this.singleCall = singleCall;
        this.aggregator = aggregator;
    }

    /**
     * 完整审查（对应 MCP tool review_pr）：预处理 → map → reduce。
     * totalLatencyMs 是整个过程的墙钟时间，包括拉取 GitHub。
     * <p>
     * 不因预处理失败抛异常：链接非法、GitHub 报错等记为一条 PREPARE 错误，返回只含 errors 的报告，
     * 保证调用方（CLI --out、MCP）总能拿到可溯源的结果。
     */
    public ReviewReport review(String prUrl, ReviewMode mode, ReviewProgressListener listener) {
        long start = System.nanoTime();
        PreparedPr pr;
        try {
            pr = prepare(prUrl);
        } catch (RuntimeException e) {
            log.error("review_prepare_failed prUrl={} error={}", prUrl, e.toString());
            ReviewError error = new ReviewError(ReviewStage.PREPARE, null, null, false, ReviewError.describe(e));
            return new ReviewReport(mode, prUrl, null, List.of(), List.of(), List.of(), List.of(), List.of(error),
                    List.of(), (System.nanoTime() - start) / 1_000_000, 0, 0);
        }
        listener.onPrepared(pr);
        log.info("review_start mode={} prUrl={} files={} skipped={}", mode, prUrl, pr.files().size(),
                pr.skippedFiles().size());
        // 每个文件完成时先写一条进度日志（MCP 模式下看不到 CLI 进度，只能看日志文件），再交给调用方的 listener
        ReviewProgressListener logged = (done, total, r) -> {
            logFileReviewed(done, total, r);
            listener.onFileReviewed(done, total, r);
        };

        List<FileReviewResult> results;
        List<String> failedFiles;
        if (mode == ReviewMode.SINGLE) {
            FileReviewResult r = singleCall.review(pr.files(), pr.summary());
            logged.onFileReviewed(1, 1, r);
            results = List.of(r);
            // 一次调用失败 = 所有文件都没审到
            failedFiles = r.failed() ? pr.files().stream().map(FileDiff::path).toList() : List.of();
        } else {
            results = mapReduce.review(pr.files(), pr.summary(), logged);
            failedFiles = results.stream().filter(FileReviewResult::failed).map(FileReviewResult::file).toList();
        }

        List<Finding> findings = results.stream().flatMap(r -> r.findings().stream()).toList();
        List<CallMetrics> calls = results.stream().flatMap(r -> r.calls().stream()).toList();
        List<ReviewError> errors = results.stream().flatMap(r -> r.errors().stream()).toList();
        long wallMs = (System.nanoTime() - start) / 1_000_000;

        ReviewReport report = new ReviewReport(mode, prUrl, pr.headSha(), pr.prFiles(), aggregator.aggregate(findings),
                failedFiles, pr.skippedFiles(), errors, calls, wallMs,
                calls.stream().mapToInt(CallMetrics::inputTokens).sum(),
                calls.stream().mapToInt(CallMetrics::outputTokens).sum());
        log.info("review_done mode={} findings={} failedFiles={} errors={} calls={} inputTokens={} outputTokens={} wallMs={}",
                mode, report.findings().size(), failedFiles.size(), errors.size(), calls.size(),
                report.totalInputTokens(), report.totalOutputTokens(), wallMs);
        return report;
    }

    /** 与 CLI 进度行对应：[3/11] file  findings  in / out（含 JSON 重试的合计）  耗时。 */
    private static void logFileReviewed(int done, int total, FileReviewResult r) {
        int in = r.calls().stream().mapToInt(CallMetrics::inputTokens).sum();
        int out = r.calls().stream().mapToInt(CallMetrics::outputTokens).sum();
        long ms = r.calls().stream().mapToLong(CallMetrics::latencyMs).sum();
        if (r.failed()) {
            log.warn("file_reviewed [{}/{}] file={} failed inputTokens={} outputTokens={} latencyMs={} error={}",
                    done, total, r.file(), in, out, ms, r.error());
        } else {
            log.info("file_reviewed [{}/{}] file={} findings={} inputTokens={} outputTokens={} latencyMs={}",
                    done, total, r.file(), r.findings().size(), in, out, ms);
        }
    }

    /** PR 链接 → 拉取 → 过滤 → 解析 patch → 生成摘要。 */
    public PreparedPr prepare(String prUrl) {
        ReviewRequest request = PrUrlParser.parse(prUrl);
        PrInfo info = gitHub.getPullRequest(request);
        List<PrFile> rawFiles = gitHub.listFiles(request);

        List<FileDiff> files = new ArrayList<>();
        List<SkippedFile> skipped = new ArrayList<>();
        for (PrFile f : rawFiles) {
            Optional<SkippedFile> skip = fileFilter.check(f);
            if (skip.isPresent()) {
                skipped.add(skip.get());
            } else {
                try {
                    files.add(patchParser.parse(f.filename(), ChangeType.fromGitHubStatus(f.status()), f.patch()));
                } catch (RuntimeException e) {
                    throw new IllegalStateException("解析 patch 失败: " + f.filename(), e); // 补上文件名，便于溯源
                }
            }
        }
        PrSummary summary = summaryBuilder.build(info.title(), files);
        List<String> prFiles = rawFiles.stream().map(PrFile::filename).toList();
        return new PreparedPr(request, prUrl, info.headSha(), prFiles, files, skipped, summary);
    }

    /**
     * 审查 PR 中的单个文件（对应 MCP tool review_file）。
     *
     * @param filePath GitHub 返回的完整路径；也接受唯一匹配的路径后缀，如 service/OrderService.java
     * @throws IllegalArgumentException 文件不在 PR 中、被跳过、或后缀匹配到多个文件
     */
    public FileReviewResult reviewFile(String prUrl, String filePath) {
        PreparedPr pr = prepare(prUrl);
        List<FileDiff> matches = pr.files().stream()
                .filter(f -> f.path().equals(filePath) || f.path().endsWith("/" + filePath))
                .toList();
        if (matches.size() == 1) {
            return fileReviewer.review(matches.get(0), pr.summary());
        }
        if (matches.size() > 1) {
            throw new IllegalArgumentException("路径 " + filePath + " 匹配到多个文件: "
                    + matches.stream().map(FileDiff::path).toList());
        }
        String skippedReason = pr.skippedFiles().stream()
                .filter(s -> s.path().equals(filePath) || s.path().endsWith("/" + filePath))
                .map(s -> s.path() + "：" + s.reason())
                .findFirst()
                .orElse(null);
        throw new IllegalArgumentException(skippedReason != null
                ? "该文件被跳过，不审查 — " + skippedReason
                : "PR 中没有文件 " + filePath);
    }
}
