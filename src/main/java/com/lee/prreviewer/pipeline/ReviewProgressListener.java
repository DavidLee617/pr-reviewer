package com.lee.prreviewer.pipeline;

import com.lee.prreviewer.map.FileReviewResult;
import com.lee.prreviewer.model.PreparedPr;

/**
 * 审查进度回调，CLI 用它实时打印进度。
 * <p>
 * mapreduce 模式下 onFileReviewed 来自工作线程，但调用是串行的（同一时刻只有一个），
 * done 按完成顺序从 1 递增到 total。
 * C# 对照：≈ IProgress&lt;T&gt;。
 */
@FunctionalInterface
public interface ReviewProgressListener {

    ReviewProgressListener NONE = (done, total, result) -> {};

    /** 预处理完成、开始调用 LLM 之前。 */
    default void onPrepared(PreparedPr pr) {}

    void onFileReviewed(int done, int total, FileReviewResult result);
}
