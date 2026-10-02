package com.lee.prreviewer.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lee.prreviewer.model.ReviewRequest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** C# 对照：@ParameterizedTest + @ValueSource ≈ xUnit 的 [Theory] + [InlineData]。 */
class PrUrlParserTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "https://github.com/DavidLee617/bookmarket/pull/12",
            "https://github.com/DavidLee617/bookmarket/pull/12/",
            "https://github.com/DavidLee617/bookmarket/pull/12/files",
            "https://github.com/DavidLee617/bookmarket/pull/12/files?w=1#diff-abc",
            "http://www.github.com/DavidLee617/bookmarket/pull/12",
            "  https://github.com/DavidLee617/bookmarket/pull/12  "
    })
    void parsesValidUrls(String url) {
        assertThat(PrUrlParser.parse(url)).isEqualTo(new ReviewRequest("DavidLee617", "bookmarket", 12));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "https://github.com/DavidLee617/bookmarket",
            "https://github.com/DavidLee617/bookmarket/issues/12",
            "https://github.com/DavidLee617/bookmarket/pull/abc",
            "https://github.com/DavidLee617/bookmarket/pull/0",
            "https://gitlab.com/DavidLee617/bookmarket/pull/12",
            "https://github.com/lee/pull/12",
            "https://github.com/DavidLee617/bookmarket/pull/99999999999",
            "github.com/DavidLee617/bookmarket/pull/12"
    })
    void rejectsInvalidUrls(String url) {
        assertThatThrownBy(() -> PrUrlParser.parse(url)).isInstanceOf(IllegalArgumentException.class);
    }
}
