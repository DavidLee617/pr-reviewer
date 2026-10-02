package com.lee.prreviewer.preprocess;

import static org.assertj.core.api.Assertions.assertThat;

import com.lee.prreviewer.github.PrFile;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class FileFilterTest {

    /** 与 application.yml 中的默认规则一致。 */
    private final FileFilter filter = new FileFilter(
            List.of("**/*.java"),
            List.of("**/target/**", "**/build/**", "**/generated/**", "**/generated-sources/**"));

    private static PrFile modified(String path) {
        return new PrFile(path, "modified", "@@ -1 +1 @@\n-a\n+b", 2, null);
    }

    @ParameterizedTest
    @CsvSource({
            "target/classes/com/example/demo/Foo.java,                     **/target/**",
            "order-service/target/generated-sources/annotations/A.java,    **/target/**",
            "build/generated/sources/B.java,                               **/build/**",
            "src/main/generated/com/example/QBook.java,                    **/generated/**",
            "module/generated-sources/C.java,                              **/generated-sources/**"
    })
    void excludesByGlob(String path, String expectedGlob) {
        assertThat(filter.check(modified(path)))
                .hasValueSatisfying(s -> assertThat(s.reason()).isEqualTo("匹配排除规则 " + expectedGlob));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "pom.xml",
            "src/main/resources/application.yml",
            "README.md",
            "docs/superpowers/specs/design.md",
            "src/main/java/com/example/demo/Book.kt"
    })
    void skipsFilesOutsideIncludeGlobs(String path) {
        assertThat(filter.check(modified(path)))
                .hasValueSatisfying(s -> assertThat(s.reason()).startsWith("不在审查范围"));
    }

    @Test
    void excludesRemovedFiles() {
        PrFile removed = new PrFile("src/main/java/com/example/demo/Old.java", "removed", "@@ -1,2 +0,0 @@\n-a\n-b", 2, null);

        assertThat(filter.check(removed)).hasValueSatisfying(s -> assertThat(s.reason()).isEqualTo("文件已删除"));
    }

    @Test
    void skipsFilesWithoutPatch() {
        PrFile huge = new PrFile("src/main/java/com/example/demo/Huge.java", "modified", null, 50000, null);

        assertThat(filter.check(huge)).hasValueSatisfying(s -> assertThat(s.reason()).startsWith("无 patch"));
    }

    @Test
    void skipsPureRenames() {
        PrFile renamed = new PrFile("src/main/java/com/example/demo/BookSvc.java", "renamed", null, 0,
                "src/main/java/com/example/demo/BookService.java");

        assertThat(filter.check(renamed)).hasValueSatisfying(s -> assertThat(s.reason()).isEqualTo("仅重命名，无内容改动"));
    }

    @Test
    void keepsNormalJavaSourceFiles() {
        assertThat(filter.check(modified("src/main/java/com/example/demo/service/BookService.java"))).isEmpty();
        assertThat(filter.check(modified("Main.java"))).isEmpty(); // 仓库根下的文件
        assertThat(filter.check(modified("src/main/java/com/example/targeting/AdTarget.java"))).isEmpty(); // 不能误伤 target 前缀
    }
}
