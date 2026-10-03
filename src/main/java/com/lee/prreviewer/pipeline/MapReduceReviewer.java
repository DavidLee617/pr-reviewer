package com.lee.prreviewer.pipeline;

import com.lee.prreviewer.config.ReviewProperties;
import com.lee.prreviewer.map.FileReviewResult;
import com.lee.prreviewer.map.FileReviewer;
import com.lee.prreviewer.model.FileDiff;
import com.lee.prreviewer.model.PrSummary;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Map 阶段：固定大小线程池（review.concurrency）并行审查每个文件。
 * 单个文件失败（包括未预期的异常）只记为该文件失败，不影响其他文件。
 * <p>
 * C# 对照：≈ Parallel.ForEachAsync(files, new ParallelOptions { MaxDegreeOfParallelism = n }, ...)；
 * ExecutorService ≈ 固定线程数的 TaskScheduler，Future ≈ Task&lt;T&gt;。
 */
@Component
public class MapReduceReviewer {

    private static final Logger log = LoggerFactory.getLogger(MapReduceReviewer.class);

    private final FileReviewer fileReviewer;
    private final int concurrency;

    public MapReduceReviewer(FileReviewer fileReviewer, ReviewProperties props) {
        this.fileReviewer = fileReviewer;
        this.concurrency = props.concurrency();
    }

    /** @return 每个文件一条结果，顺序与输入相同（不是完成顺序） */
    public List<FileReviewResult> review(List<FileDiff> files, PrSummary summary, ReviewProgressListener listener) {
        int total = files.size();
        AtomicInteger done = new AtomicInteger();
        Object progressLock = new Object();

        // try-with-resources：close() 会等所有任务结束再关闭线程池（≈ using + await Task.WhenAll）
        try (ExecutorService pool = Executors.newFixedThreadPool(concurrency)) {
            List<Future<FileReviewResult>> futures = new ArrayList<>(total);
            for (FileDiff file : files) {
                futures.add(pool.submit(() -> {
                    FileReviewResult result = reviewOne(file, summary);
                    synchronized (progressLock) { // 保证进度编号与回调顺序一致
                        listener.onFileReviewed(done.incrementAndGet(), total, result);
                    }
                    return result;
                }));
            }
            List<FileReviewResult> results = new ArrayList<>(total);
            for (Future<FileReviewResult> f : futures) {
                results.add(f.get());
            }
            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("审查被中断", e);
        } catch (ExecutionException e) {
            // reviewOne 不抛异常，到这里只可能是进度回调本身出错
            throw new IllegalStateException("审查任务异常: " + e.getCause(), e.getCause());
        }
    }

    private FileReviewResult reviewOne(FileDiff file, PrSummary summary) {
        try {
            return fileReviewer.review(file, summary);
        } catch (RuntimeException e) {
            // FileReviewer 已处理 LLM 和 JSON 错误；这里兜住其余意外，保留原始异常信息
            log.error("file_review_failed file={} error=未预期异常", file.path(), e);
            return new FileReviewResult(file.path(), List.of(), List.of(), "未预期异常: " + e);
        }
    }
}
