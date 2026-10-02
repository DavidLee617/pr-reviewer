package com.lee.prreviewer.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.lee.prreviewer.config.GitHubProperties;
import com.lee.prreviewer.model.ReviewRequest;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 用 MockRestServiceServer 模拟 GitHub，不发真实请求。
 * C# 对照：≈ 给 HttpClient 注入一个假的 HttpMessageHandler（或用 WireMock.Net / RichardSzalay.MockHttp）。
 */
class GitHubPrClientTest {

    private static final String BASE = "https://api.github.com/repos/DavidLee617/bookmarket/pulls/7";
    private final ReviewRequest req = new ReviewRequest("DavidLee617", "bookmarket", 7);

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final GitHubPrClient client =
            new GitHubPrClient(builder, new GitHubProperties("https://api.github.com", "test-token"));

    private static String filesJson(int from, int count) {
        return IntStream.range(from, from + count)
                .mapToObj(i -> """
                        {"filename":"src/main/java/S%d.java","status":"modified","changes":2,"patch":"@@ -1 +1 @@\\n-a\\n+b","sha":"x"}"""
                        .formatted(i))
                .collect(Collectors.joining(",", "[", "]"));
    }

    @Test
    void fetchesPrTitleAndHeadShaWithAuthHeader() {
        server.expect(requestTo(BASE))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
                .andRespond(withSuccess("""
                        {"title":"Add search","number":7,"head":{"sha":"abc123","ref":"feature"}}""",
                        MediaType.APPLICATION_JSON));

        assertThat(client.getPullRequest(req)).isEqualTo(new PrInfo("Add search", "abc123"));
        server.verify();
    }

    @Test
    void paginatesUntilEmptyPage() {
        server.expect(requestTo(BASE + "/files?per_page=100&page=1"))
                .andRespond(withSuccess(filesJson(0, 100), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/files?per_page=100&page=2"))
                .andRespond(withSuccess(filesJson(100, 30), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/files?per_page=100&page=3"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        List<PrFile> files = client.listFiles(req);

        assertThat(files).hasSize(130);
        assertThat(files.get(129).filename()).isEqualTo("src/main/java/S129.java");
        server.verify();
    }

    @Test
    void keepsFilesWithMissingPatch() {
        server.expect(requestTo(BASE + "/files?per_page=100&page=1"))
                .andRespond(withSuccess("""
                        [{"filename":"src/main/resources/static/logo.png","status":"added","changes":0},
                         {"filename":"Huge.java","status":"modified","changes":50000}]""",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/files?per_page=100&page=2"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        List<PrFile> files = client.listFiles(req);

        assertThat(files).extracting(PrFile::patch).containsOnlyNulls();
        assertThat(files).extracting(PrFile::filename).containsExactly("src/main/resources/static/logo.png", "Huge.java");
    }

    @Test
    void notFoundCarriesStatusAndGitHubMessage() {
        server.expect(requestTo(BASE))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"Not Found\",\"documentation_url\":\"https://docs.github.com\"}"));

        assertThatThrownBy(() -> client.getPullRequest(req))
                .isInstanceOf(GitHubApiException.class)
                .hasMessageContaining("HTTP 404")
                .hasMessageContaining("GitHub message: Not Found")
                .satisfies(e -> assertThat(((GitHubApiException) e).statusCode()).isEqualTo(404));
    }

    @Test
    void rateLimitIsReportedExplicitly() {
        server.expect(requestTo(BASE))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .header("x-ratelimit-remaining", "0")
                        .header("x-ratelimit-reset", "1790000000")
                        .body("{\"message\":\"API rate limit exceeded\"}"));

        assertThatThrownBy(() -> client.getPullRequest(req))
                .isInstanceOf(GitHubApiException.class)
                .hasMessageContaining("速率限制")
                .hasMessageContaining("HTTP 403")
                .hasMessageContaining("API rate limit exceeded");
    }

    @Test
    void unauthorizedIsReported() {
        server.expect(requestTo(BASE))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("{\"message\":\"Bad credentials\"}"));

        assertThatThrownBy(() -> client.getPullRequest(req))
                .hasMessageContaining("HTTP 401")
                .hasMessageContaining("Bad credentials");
    }
}
