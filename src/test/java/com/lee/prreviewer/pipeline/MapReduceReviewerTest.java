package com.lee.prreviewer.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lee.prreviewer.config.ReviewProperties;
import com.lee.prreviewer.map.FileReviewResult;
import com.lee.prreviewer.map.FileReviewer;
import com.lee.prreviewer.model.Category;
import com.lee.prreviewer.model.ChangeType;
import com.lee.prreviewer.model.FileDiff;
import com.lee.prreviewer.model.Finding;
import com.lee.prreviewer.model.PrSummary;
import com.lee.prreviewer.model.Severity;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** 用假的 FileReviewer 测试并发、失败隔离和进度回调，不调真实 LLM。 */
class MapReduceReviewerTest {

    private static final PrSummary SUMMARY = new PrSummary("t", List.of(), List.of());

    private final FileReviewer fileReviewer = mock(FileReviewer.class);

    private static MapReduceReviewer reviewer(FileReviewer fr, int concurrency) {
        return new MapReduceReviewer(fr, new ReviewProperties(concurrency, 2, 1500,
                new ReviewProperties.Filter(List.of(), List.of())));
    }

    private static FileDiff diff(String path) {
        return new FileDiff(path, ChangeType.MODIFIED, List.of(), "");
    }

    private static FileReviewResult ok(String path) {
        return new FileReviewResult(path, List.of(new Finding(path, 1, Category.STYLE, Severity.LOW, "m", "s")),
                List.of(), null);
    }

    @Test
    void failedAndThrowingFilesDoNotAffectOthers() {
        when(fileReviewer.review(any(), any())).thenAnswer(inv -> {
            String path = inv.<FileDiff>getArgument(0).path();
            return switch (path) {
                case "bad.java" -> new FileReviewResult(path, List.of(), List.of(), "LLM 调用失败");
                case "boom.java" -> throw new IllegalStateException("意外");
                default -> ok(path);
            };
        });

        List<FileReviewResult> results = reviewer(fileReviewer, 2).review(
                List.of(diff("a.java"), diff("bad.java"), diff("boom.java"), diff("b.java")),
                SUMMARY, ReviewProgressListener.NONE);

        // 顺序与输入相同
        assertThat(results).extracting(FileReviewResult::file)
                .containsExactly("a.java", "bad.java", "boom.java", "b.java");
        assertThat(results).extracting(FileReviewResult::failed).containsExactly(false, true, true, false);
        assertThat(results.get(2).error()).contains("IllegalStateException").contains("意外");
        assertThat(results.get(3).findings()).hasSize(1);
    }

    @Test
    void progressCountsFromOneToTotal() {
        when(fileReviewer.review(any(), any())).thenAnswer(inv -> ok(inv.<FileDiff>getArgument(0).path()));
        List<FileDiff> files = IntStream.range(0, 20).mapToObj(i -> diff("f" + i + ".java")).toList();
        List<Integer> done = Collections.synchronizedList(new ArrayList<>());
        List<Integer> totals = Collections.synchronizedList(new ArrayList<>());

        reviewer(fileReviewer, 4).review(files, SUMMARY, (d, total, r) -> {
            done.add(d);
            totals.add(total);
        });

        assertThat(done).containsExactlyElementsOf(IntStream.rangeClosed(1, 20).boxed().toList());
        assertThat(totals).containsOnly(20);
    }

    @Test
    void runsFilesConcurrently() throws Exception {
        // 两个文件互相等待：只有真正并行时才能都通过 latch，串行执行会超时
        CountDownLatch bothStarted = new CountDownLatch(2);
        when(fileReviewer.review(any(), any())).thenAnswer(inv -> {
            bothStarted.countDown();
            if (!bothStarted.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("未并行执行");
            }
            return ok(inv.<FileDiff>getArgument(0).path());
        });

        List<FileReviewResult> results = reviewer(fileReviewer, 2).review(
                List.of(diff("a.java"), diff("b.java")), SUMMARY, ReviewProgressListener.NONE);

        assertThat(results).noneMatch(FileReviewResult::failed);
    }
}
