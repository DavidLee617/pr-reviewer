package com.lee.prreviewer.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.lee.prreviewer.eval.EvalResult.BugResult;
import com.lee.prreviewer.eval.EvalResult.Ratio;
import com.lee.prreviewer.eval.EvalResult.Segment;
import com.lee.prreviewer.eval.GroundTruth.Location;
import com.lee.prreviewer.eval.GroundTruth.SeededBug;
import com.lee.prreviewer.model.Category;
import com.lee.prreviewer.model.Finding;
import com.lee.prreviewer.model.ReviewMode;
import com.lee.prreviewer.model.ReviewReport;
import com.lee.prreviewer.model.Severity;
import com.lee.prreviewer.model.SkippedFile;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecallEvaluatorTest {

    private static final String URL = "https://github.com/o/r/pull/1";
    private static final String SHA = "7fd23c0c1b04ab126d10459e5c0dc4de6d15624c";
    // 6 个文件：前段 A、B，中段 C、D，后段 E、application.yml（被跳过）
    private static final List<String> PR_FILES = List.of("A.java", "B.java", "C.java", "D.java", "E.java", "application.yml");

    private final RecallEvaluator evaluator = new RecallEvaluator();

    private static SeededBug bug(String id, boolean crossFile, List<Category> categories, Location... locations) {
        return new SeededBug(id, Severity.HIGH, crossFile, categories, List.of(locations), "note " + id);
    }

    private static Finding finding(String file, int line, Category c) {
        return new Finding(file, line, c, Severity.HIGH, "msg " + file + ":" + line, "");
    }

    private static ReviewReport report(String sha, Finding... findings) {
        return new ReviewReport(ReviewMode.MAPREDUCE, URL, sha, PR_FILES, List.of(findings), List.of(),
                List.of(new SkippedFile("application.yml", "不在审查范围")), List.of(), List.of(), 0, 0, 0);
    }

    private static GroundTruth truth(SeededBug... bugs) {
        return new GroundTruth(URL, SHA, "test", List.of(bugs));
    }

    @Test
    void lineToleranceBoundaryIsThree() {
        SeededBug b = bug("B1", false, List.of(Category.LOGIC), new Location("C.java", 40, 42));

        assertThat(RecallEvaluator.hits(finding("C.java", 37, Category.LOGIC), b)).isTrue();  // lineStart - 3
        assertThat(RecallEvaluator.hits(finding("C.java", 45, Category.LOGIC), b)).isTrue();  // lineEnd + 3
        assertThat(RecallEvaluator.hits(finding("C.java", 36, Category.LOGIC), b)).isFalse(); // lineStart - 4
        assertThat(RecallEvaluator.hits(finding("C.java", 46, Category.LOGIC), b)).isFalse(); // lineEnd + 4
        assertThat(RecallEvaluator.hits(finding("D.java", 40, Category.LOGIC), b)).isFalse(); // 文件不同
    }

    @Test
    void oneFindingHitsAtMostOneBugPreferringClosest() {
        // 复现 PR #1：B10 在 57 行，B11 在 56 行；只有一条 57 行的 finding（说的是 B10）
        SeededBug b10 = bug("B10", false, List.of(Category.LOGIC), new Location("E.java", 57, 57));
        SeededBug b11 = bug("B11", true, List.of(Category.LOGIC),
                new Location("A.java", 15, 15), new Location("E.java", 56, 56));

        EvalResult r = evaluator.evaluate(report(SHA, finding("E.java", 57, Category.LOGIC)), truth(b11, b10));

        assertThat(r.bugs()).extracting(BugResult::id, BugResult::hit)
                .containsExactly(tuple("B11", false), tuple("B10", true));
    }

    @Test
    void denseBugsAreMatchedSeparatelyWhenEachHasItsFinding() {
        // 复现 PR #1：78 命名、79 魔法值、80-81 double，三条 finding 各配各的
        SeededBug b14 = bug("B14", false, List.of(Category.LOGIC), new Location("E.java", 80, 81));
        SeededBug b15 = bug("B15", false, List.of(Category.STYLE, Category.SECURITY), new Location("E.java", 79, 79));
        SeededBug b16 = bug("B16", false, List.of(Category.STYLE, Category.NAMING), new Location("E.java", 78, 78));

        EvalResult only80 = evaluator.evaluate(report(SHA, finding("E.java", 80, Category.LOGIC)), truth(b14, b15, b16));
        EvalResult all = evaluator.evaluate(report(SHA,
                finding("E.java", 80, Category.LOGIC),
                finding("E.java", 78, Category.NAMING),
                finding("E.java", 79, Category.STYLE)), truth(b14, b15, b16));

        assertThat(only80.overall()).isEqualTo(new Ratio(1, 3));
        assertThat(all.overall()).isEqualTo(new Ratio(3, 3));
        assertThat(all.categoryAgreement()).isEqualTo(new Ratio(3, 3));
        assertThat(all.bugs()).extracting(b -> b.matched().get(0).line()).containsExactly(80, 79, 78);
    }

    @Test
    void categoryMatchBeatsCloserLine() {
        // 复现 PR #1：B05（33 行，返回实体）真正对应的是 36 行 SECURITY，而不是 34 行 STYLE（System.out）
        SeededBug b04 = bug("B04", false, List.of(Category.SECURITY), new Location("A.java", 34, 34));
        SeededBug b05 = bug("B05", false, List.of(Category.SECURITY), new Location("A.java", 33, 33));

        EvalResult r = evaluator.evaluate(report(SHA,
                finding("A.java", 34, Category.SECURITY),
                finding("A.java", 34, Category.STYLE),
                finding("A.java", 36, Category.SECURITY)), truth(b04, b05));

        assertThat(r.bugs()).extracting(b -> b.matched().get(0).line() + " " + b.matched().get(0).category())
                .containsExactly("34 SECURITY", "36 SECURITY");
    }

    @Test
    void sameDistancePrefersCategoryMatch() {
        SeededBug style = bug("S", false, List.of(Category.STYLE), new Location("E.java", 10, 10));
        SeededBug sec = bug("X", false, List.of(Category.SECURITY), new Location("E.java", 12, 12));

        // 11 行的两条 finding 到两个埋点都是距离 1，按类别配对
        EvalResult r = evaluator.evaluate(report(SHA,
                finding("E.java", 11, Category.SECURITY),
                finding("E.java", 11, Category.STYLE)), truth(style, sec));

        assertThat(r.categoryAgreement()).isEqualTo(new Ratio(2, 2));
    }

    @Test
    void crossFileBugHitsOnAnyLocation() {
        SeededBug b = bug("B1", true, List.of(Category.LOGIC),
                new Location("A.java", 15, 15), new Location("E.java", 56, 56));

        EvalResult r = evaluator.evaluate(report(SHA, finding("E.java", 57, Category.LOGIC)), truth(b));

        assertThat(r.crossFile()).isEqualTo(new Ratio(1, 1));
        assertThat(r.singleFile()).isEqualTo(new Ratio(0, 0));
        // 分段按第一个（主）位置
        assertThat(r.bugs().get(0).segment()).isEqualTo(Segment.FRONT);
    }

    @Test
    void ratiosSegmentsScopeAndCategoryAgreement() {
        EvalResult r = evaluator.evaluate(report(SHA,
                        finding("A.java", 10, Category.SECURITY),   // 命中 B1，类别一致
                        finding("C.java", 20, Category.STYLE),      // 命中 B2，类别不一致
                        finding("E.java", 99, Category.STYLE)),     // 未匹配
                truth(
                        bug("B1", false, List.of(Category.SECURITY), new Location("A.java", 10, 10)),
                        bug("B2", false, List.of(Category.LOGIC), new Location("C.java", 20, 20)),
                        bug("B3", false, List.of(Category.LOGIC), new Location("E.java", 5, 5)),
                        bug("B4", false, List.of(Category.SECURITY), new Location("application.yml", 26, 26))));

        assertThat(r.overall()).isEqualTo(new Ratio(2, 4));
        assertThat(r.inScope()).isEqualTo(new Ratio(2, 3));
        assertThat(r.bySegment()).containsEntry(Segment.FRONT, new Ratio(1, 1))
                .containsEntry(Segment.MIDDLE, new Ratio(1, 1))
                .containsEntry(Segment.BACK, new Ratio(0, 2));
        assertThat(r.segmentFiles().get(Segment.BACK)).containsExactly("E.java", "application.yml");
        assertThat(r.categoryAgreement()).isEqualTo(new Ratio(1, 2));
        assertThat(r.unmatchedFindings()).extracting(Finding::line).containsExactly(99);
        assertThat(r.bugs()).extracting(BugResult::inScope).containsExactly(true, true, true, false);
        assertThat(r.warnings()).isEmpty();
    }

    @Test
    void warnsWhenCommitDiffers() {
        EvalResult r = evaluator.evaluate(report("abcdef0"), truth(bug("B1", false, List.of(Category.LOGIC),
                new Location("A.java", 1, 1))));

        assertThat(r.warnings()).anySatisfy(w -> assertThat(w).contains("abcdef0", "行号可能已偏移"));
    }

    @Test
    void shortShaInTruthIsAccepted() {
        GroundTruth t = new GroundTruth(URL, "7fd23c0", "test", List.of());

        assertThat(evaluator.evaluate(report(SHA), t).warnings()).isEmpty();
    }
}
