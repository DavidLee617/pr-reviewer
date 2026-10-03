package com.lee.prreviewer.eval;

import com.lee.prreviewer.model.Category;
import com.lee.prreviewer.model.Severity;
import java.util.List;

/**
 * 标准答案 ground_truth.json（人工维护，不能由同一模型生成，设计文档第 9、11 节）。
 *
 * @param prUrl   答案对应的 PR；与报告不一致时 eval 给出警告
 * @param headSha 答案的行号所依据的提交；与报告不一致时行号可能已偏移，eval 给出警告
 * @param source  答案来源说明（仅供人看）
 */
public record GroundTruth(String prUrl, String headSha, String source, List<SeededBug> bugs) {

    public GroundTruth {
        bugs = List.copyOf(bugs);
    }

    /**
     * 一个埋点。
     *
     * @param categories 可接受的类别（只用于统计类别一致率，不影响命中）；一个问题可能同时属于多类
     * @param crossFile  是否跨文件：单看一个文件发现不了，用来测 map-reduce 的已知弱点
     * @param locations  问题所在位置；跨文件的雷有多个位置，命中任一即算命中。第一个为主位置，决定所在分段
     */
    public record SeededBug(String id, Severity severity, boolean crossFile, List<Category> categories,
                            List<Location> locations, String note) {
        public SeededBug {
            categories = List.copyOf(categories);
            locations = List.copyOf(locations);
        }
    }

    /** @param lineStart 新文件（PR head 版本）中的行号，闭区间 */
    public record Location(String file, int lineStart, int lineEnd) {}
}
