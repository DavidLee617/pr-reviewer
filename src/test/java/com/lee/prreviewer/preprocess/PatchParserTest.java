package com.lee.prreviewer.preprocess;

import static org.assertj.core.api.Assertions.assertThat;

import com.lee.prreviewer.model.ChangeType;
import com.lee.prreviewer.model.DiffLine;
import com.lee.prreviewer.model.FileDiff;
import com.lee.prreviewer.model.Hunk;
import com.lee.prreviewer.model.LineType;
import org.junit.jupiter.api.Test;

class PatchParserTest {

    private final PatchParser parser = new PatchParser();

    @Test
    void annotatesNewLineNumbersAndRemovedPlaceholder() {
        String patch = String.join("\n",
                "@@ -40,4 +41,4 @@ public class BookService",
                "     public Book getById(Long id) {",
                "+        log.info(\"get {}\", id);",
                "-        return repo.getOne(id);",
                "+        return repo.findById(id).orElseThrow();",
                "     }");

        FileDiff diff = parser.parse("BookService.java", ChangeType.MODIFIED, patch);

        assertThat(diff.annotatedDiff()).isEqualTo(String.join("\n",
                "File: BookService.java",
                "  41 |       public Book getById(Long id) {",
                "  42 | +         log.info(\"get {}\", id);",
                "   - | -         return repo.getOne(id);",
                "  43 | +         return repo.findById(id).orElseThrow();",
                "  44 |       }",
                ""));
    }

    @Test
    void parsesMultipleHunksWithCorrectLineNumbers() {
        String patch = String.join("\n",
                "@@ -1,3 +1,4 @@",
                " package com.example.demo;",
                "+import java.util.List;",
                " ",
                " import java.util.Map;",
                "@@ -20,3 +21,2 @@ public class BookService",
                "     var a = 1;",
                "-    var b = 2;",
                "     return a;");

        FileDiff diff = parser.parse("BookService.java", ChangeType.MODIFIED, patch);

        assertThat(diff.hunks()).hasSize(2);
        Hunk first = diff.hunks().get(0);
        assertThat(first.newStart()).isEqualTo(1);
        assertThat(first.newCount()).isEqualTo(4);
        assertThat(first.lines()).extracting(DiffLine::newLineNo).containsExactly(1, 2, 3, 4);
        assertThat(first.lines().get(1)).isEqualTo(new DiffLine(LineType.ADDED, 2, "import java.util.List;"));
        assertThat(first.lines().get(2).content()).isEqualTo(""); // 空上下文行

        Hunk second = diff.hunks().get(1);
        assertThat(second.newStart()).isEqualTo(21);
        assertThat(second.lines()).extracting(DiffLine::newLineNo).containsExactly(21, null, 22);
        assertThat(diff.annotatedDiff()).contains(" ... |\n");
    }

    @Test
    void parsesPureAddedFile() {
        String patch = String.join("\n",
                "@@ -0,0 +1,3 @@",
                "+package com.example.demo.entity;",
                "+",
                "+public record Author(Long id, String name) {}");

        FileDiff diff = parser.parse("Author.java", ChangeType.ADDED, patch);

        assertThat(diff.hunks()).singleElement().satisfies(h -> {
            assertThat(h.newStart()).isEqualTo(1);
            assertThat(h.lines()).allMatch(l -> l.type() == LineType.ADDED);
            assertThat(h.lines()).extracting(DiffLine::newLineNo).containsExactly(1, 2, 3);
        });
    }

    @Test
    void stripsCrlfAndIgnoresNoNewlineMarker() {
        String patch = "@@ -1 +1 @@\r\n-\uFEFFvar a = 1;\r\n+var a = 2;\r\n\\ No newline at end of file";

        FileDiff diff = parser.parse("A.java", ChangeType.MODIFIED, patch);

        assertThat(diff.hunks().get(0).newCount()).isEqualTo(1); // 省略 count 表示 1
        assertThat(diff.hunks().get(0).lines()).containsExactly(
                new DiffLine(LineType.REMOVED, null, "var a = 1;"),
                new DiffLine(LineType.ADDED, 1, "var a = 2;"));
    }

    @Test
    void widensLineNumberColumnForLargeFiles() {
        FileDiff diff = parser.parse("Big.java", ChangeType.MODIFIED, "@@ -10000,1 +10000,1 @@\n-x\n+y");

        assertThat(diff.annotatedDiff()).contains("10000 | + y");
    }
}
