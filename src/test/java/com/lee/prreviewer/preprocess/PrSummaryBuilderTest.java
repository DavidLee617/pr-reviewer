package com.lee.prreviewer.preprocess;

import static org.assertj.core.api.Assertions.assertThat;

import com.lee.prreviewer.model.ChangeType;
import com.lee.prreviewer.model.FileDiff;
import com.lee.prreviewer.model.PrSummary;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PrSummaryBuilderTest {

    private static final String PATH = "src/main/java/com/example/demo/service/BookService.java";
    private final PatchParser parser = new PatchParser();

    @Test
    void detectsAddedAndRemovedPublicSignatures() {
        FileDiff diff = parser.parse(PATH, ChangeType.MODIFIED, String.join("\n",
                "@@ -1,14 +1,16 @@",
                "-public class BookService {",
                "+public class BookService implements IBookService {",
                "-    public Book getById(Long id) {",
                "+    public Optional<Book> getById(Long id) throws NotFoundException {",
                "+    public BookService(BookRepository repo) {",
                "+    public static <T> List<T> page(List<T> all, int n) { return all; }",
                "+    public abstract void audit();",
                "+    public static final int MAX = 10;",                // 字段：不收录
                "+    public Comparator<Book> byTitle = (a, b) -> 0;",  // 字段（lambda）：不收录
                "+    private void helper() { }",                       // 非 public：不收录
                "+    @GetMapping(\"/{id}\")",                           // 注解行：不收录
                "+    public enum Status { ACTIVE, DELETED }",
                "+    public record BookDto(Long id, String title) {}",
                "+    public @interface Audited {}",
                "     public void unchanged() { }"));                     // 上下文行：不收录

        PrSummary summary = new PrSummaryBuilder(1500).build("Add order payment", List.of(diff));

        assertThat(summary.title()).isEqualTo("Add order payment");
        assertThat(summary.changedFiles()).containsExactly(PATH + " (MODIFIED)");
        assertThat(summary.changedPublicSignatures()).containsExactly(
                "- " + PATH + ": public class BookService",
                "+ " + PATH + ": public class BookService implements IBookService",
                "- " + PATH + ": public Book getById(Long id)",
                "+ " + PATH + ": public Optional<Book> getById(Long id) throws NotFoundException",
                "+ " + PATH + ": public BookService(BookRepository repo)",
                "+ " + PATH + ": public static <T> List<T> page(List<T> all, int n)",
                "+ " + PATH + ": public abstract void audit()",
                "+ " + PATH + ": public enum Status",
                "+ " + PATH + ": public record BookDto(Long id, String title)",
                "+ " + PATH + ": public @interface Audited");
    }

    @Test
    void truncatesWhenOverBudgetAndSaysSo() {
        List<FileDiff> files = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            files.add(parser.parse("src/main/java/com/example/demo/generated/VeryLongServiceName" + i + ".java",
                    ChangeType.ADDED, "@@ -0,0 +1,1 @@\n+public void method" + i + "(Long id) {"));
        }

        PrSummary summary = new PrSummaryBuilder(1500).build("Big PR", files);

        assertThat(PrSummaryBuilder.estimateTokens(summary.render())).isLessThanOrEqualTo(1500);
        // 签名先被删光（只剩一条截断说明），再删文件
        assertThat(summary.changedPublicSignatures()).singleElement().asString().contains("省略 300 个签名");
        assertThat(summary.changedFiles()).last().asString().contains("已截断");
    }
}
