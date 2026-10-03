package com.lee.prreviewer.reduce;

import com.lee.prreviewer.model.Category;
import com.lee.prreviewer.model.Finding;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Reduce（纯代码，不调用 LLM）：合并 → 按 file + line + category 去重（保留 severity 最高的）→ 排序。
 * <p>
 * C# 对照：≈ findings.GroupBy(f =&gt; (f.File, f.Line, f.Category)).Select(g =&gt; g.MinBy(f =&gt; f.Severity))
 * .OrderBy(f =&gt; f.Severity).ThenBy(f =&gt; f.File).ThenBy(f =&gt; f.Line)。
 */
@Component
public class FindingAggregator {

    /** severity（HIGH → LOW，依赖枚举声明顺序）→ file → line；category 只用来让结果稳定。 */
    static final Comparator<Finding> ORDER = Comparator.comparing(Finding::severity)
            .thenComparing(Finding::file, Comparator.nullsLast(Comparator.naturalOrder())) // single 模式下 LLM 可能漏填 file
            .thenComparingInt(Finding::line)
            .thenComparing(Finding::category);

    private record Key(String file, int line, Category category) {}

    public List<Finding> aggregate(Collection<Finding> findings) {
        Map<Key, Finding> best = new LinkedHashMap<>();
        for (Finding f : findings) {
            // 重复时保留 severity 更高（ordinal 更小）的；相同则保留先出现的
            best.merge(new Key(f.file(), f.line(), f.category()), f,
                    (kept, incoming) -> incoming.severity().compareTo(kept.severity()) < 0 ? incoming : kept);
        }
        return best.values().stream().sorted(ORDER).toList();
    }
}
