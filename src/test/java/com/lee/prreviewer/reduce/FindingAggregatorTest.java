package com.lee.prreviewer.reduce;

import static org.assertj.core.api.Assertions.assertThat;

import com.lee.prreviewer.model.Category;
import com.lee.prreviewer.model.Finding;
import com.lee.prreviewer.model.Severity;
import java.util.List;
import org.junit.jupiter.api.Test;

class FindingAggregatorTest {

    private static final String A = "src/main/java/A.java";
    private static final String B = "src/main/java/B.java";

    private final FindingAggregator aggregator = new FindingAggregator();

    private static Finding f(String file, int line, Category c, Severity s, String msg) {
        return new Finding(file, line, c, s, msg, "");
    }

    @Test
    void duplicateKeepsHighestSeverity() {
        List<Finding> result = aggregator.aggregate(List.of(
                f(A, 10, Category.SECURITY, Severity.LOW, "low"),
                f(A, 10, Category.SECURITY, Severity.HIGH, "high"),
                f(A, 10, Category.SECURITY, Severity.MEDIUM, "medium")));

        assertThat(result).containsExactly(f(A, 10, Category.SECURITY, Severity.HIGH, "high"));
    }

    @Test
    void sameSeverityDuplicateKeepsFirst() {
        List<Finding> result = aggregator.aggregate(List.of(
                f(A, 10, Category.STYLE, Severity.MEDIUM, "first"),
                f(A, 10, Category.STYLE, Severity.MEDIUM, "second")));

        assertThat(result).extracting(Finding::message).containsExactly("first");
    }

    @Test
    void differentCategoryOrLineOrFileIsNotDuplicate() {
        List<Finding> result = aggregator.aggregate(List.of(
                f(A, 10, Category.STYLE, Severity.LOW, "style"),
                f(A, 10, Category.NAMING, Severity.LOW, "naming"),
                f(A, 11, Category.STYLE, Severity.LOW, "next line"),
                f(B, 10, Category.STYLE, Severity.LOW, "other file")));

        assertThat(result).hasSize(4);
    }

    @Test
    void sortsBySeverityThenFileThenLine() {
        List<Finding> result = aggregator.aggregate(List.of(
                f(B, 5, Category.STYLE, Severity.LOW, "B5 low"),
                f(B, 1, Category.STYLE, Severity.HIGH, "B1 high"),
                f(A, 30, Category.STYLE, Severity.MEDIUM, "A30 medium"),
                f(A, 20, Category.STYLE, Severity.HIGH, "A20 high"),
                f(A, 3, Category.STYLE, Severity.HIGH, "A3 high")));

        assertThat(result).extracting(Finding::message)
                .containsExactly("A3 high", "A20 high", "B1 high", "A30 medium", "B5 low");
    }

    @Test
    void emptyInputGivesEmptyResult() {
        assertThat(aggregator.aggregate(List.of())).isEmpty();
    }
}
