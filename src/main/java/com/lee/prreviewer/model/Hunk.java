package com.lee.prreviewer.model;

import java.util.List;

/**
 * 一个 hunk，对应 patch 中一段 "@@ -a,b +newStart,newCount @@"。
 * C# 对照：List&lt;DiffLine&gt; ≈ IReadOnlyList&lt;DiffLine&gt;（构造时用 List.copyOf 做防御性拷贝即不可变）。
 */
public record Hunk(int newStart, int newCount, List<DiffLine> lines) {
    public Hunk {
        // 紧凑构造函数（compact constructor），≈ C# record 里在 init 访问器中做校验/拷贝
        lines = List.copyOf(lines);
    }
}
