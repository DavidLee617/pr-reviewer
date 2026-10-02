package com.lee.prreviewer.preprocess;

import com.lee.prreviewer.config.ReviewProperties;
import com.lee.prreviewer.model.DiffLine;
import com.lee.prreviewer.model.FileDiff;
import com.lee.prreviewer.model.LineType;
import com.lee.prreviewer.model.PrSummary;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 生成 PR 摘要（不调用 LLM）。
 * <p>
 * changedPublicSignatures 是 v1 的启发式：在 ADDED / REMOVED 行里找以 public 开头的 Java 声明——
 * 类型声明（class / interface / enum / record / @interface）和方法 / 构造函数（行内有 "(" 且 "(" 之前没有 "="）。
 * 已知局限：接口方法通常省略 public，不会被识别；字段不收录；注解单独占一行时只取声明那一行；
 * 多行参数列表只取第一行。
 */
@Component
public class PrSummaryBuilder {

    /** public [修饰符...] class|interface|enum|record|@interface Name —— Java 类型声明 */
    private static final Pattern JAVA_TYPE_DECL = Pattern.compile(
            "^public\\s+(?:[a-z]+\\s+)*(?:class|interface|enum|record|@interface)\\s+\\w+.*");
    /** 截断时估算 token 用：代码文本约 3 字符/token，偏保守。仅用于控制摘要长度，不用于计量。 */
    private static final int CHARS_PER_TOKEN = 3;

    private final int maxTokens;

    @Autowired
    public PrSummaryBuilder(ReviewProperties props) {
        this(props.summaryMaxTokens());
    }

    PrSummaryBuilder(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public PrSummary build(String title, List<FileDiff> files) {
        List<String> changedFiles = files.stream()
                .map(f -> f.path() + " (" + f.changeType() + ")")
                .toList(); // ≈ LINQ .Select(...).ToList()

        // LinkedHashSet：去重且保持插入顺序（≈ C# 里 HashSet + List 组合，或 .Distinct()）
        Set<String> signatures = new LinkedHashSet<>();
        for (FileDiff f : files) {
            f.hunks().forEach(h -> h.lines().forEach(l -> {
                String sig = extractSignature(l);
                if (sig != null) {
                    signatures.add((l.type() == LineType.ADDED ? "+ " : "- ") + f.path() + ": " + sig);
                }
            }));
        }
        return fitToBudget(title == null ? "" : title, changedFiles, new ArrayList<>(signatures));
    }

    /** 不是 public 签名时返回 null。 */
    static String extractSignature(DiffLine line) {
        if (line.type() == LineType.CONTEXT) {
            return null;
        }
        String code = line.content().strip();
        if (!code.startsWith("public ")) {
            return null;
        }
        boolean isType = JAVA_TYPE_DECL.matcher(code).matches();
        int paren = code.indexOf('(');
        int assign = code.indexOf('=');
        boolean isMethod = paren > 0 && (assign < 0 || assign > paren);
        if (!isType && !isMethod) {
            return null; // 字段
        }
        return trimBody(code);
    }

    /** 去掉方法体 / 行尾分号，只留签名。 */
    private static String trimBody(String code) {
        int cut = code.length();
        for (String token : new String[] {"{", ";"}) {
            int i = code.indexOf(token);
            if (i >= 0 && i < cut) {
                cut = i;
            }
        }
        return code.substring(0, cut).strip();
    }

    /** 超出 token 预算时，先从后往前删签名，再删文件，并在列表末尾注明"已截断"。 */
    private PrSummary fitToBudget(String title, List<String> files, List<String> sigs) {
        int keepSigs = sigs.size();
        int keepFiles = files.size();
        while (true) {
            PrSummary candidate = new PrSummary(title,
                    truncate(files, keepFiles, "个文件"),
                    truncate(sigs, keepSigs, "个签名"));
            boolean fits = estimateTokens(candidate.render()) <= maxTokens;
            if (fits || (keepSigs == 0 && keepFiles == 0)) {
                return candidate;
            }
            if (keepSigs > 0) {
                keepSigs--;
            } else {
                keepFiles--;
            }
        }
    }

    private static List<String> truncate(List<String> items, int keep, String unit) {
        if (keep >= items.size()) {
            return items;
        }
        List<String> out = new ArrayList<>(items.subList(0, keep));
        out.add("…（已截断，省略 " + (items.size() - keep) + " " + unit + "）");
        return out;
    }

    static int estimateTokens(String text) {
        return (text.length() + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN;
    }
}
