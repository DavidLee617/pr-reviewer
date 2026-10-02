package com.lee.prreviewer.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.lee.prreviewer.config.GitHubProperties;
import com.lee.prreviewer.model.ReviewRequest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 拉取 PR 信息和文件列表（GitHub REST API）。
 * <p>
 * C# 对照：RestClient ≈ 配好 BaseAddress / DefaultRequestHeaders 的 HttpClient（typed client 模式）；
 * RestClient.Builder 由 Spring Boot 注入（≈ IHttpClientFactory），body(T.class) ≈ GetFromJsonAsync&lt;T&gt;()。
 */
@Component
public class GitHubPrClient {

    private static final Logger log = LoggerFactory.getLogger(GitHubPrClient.class);
    static final int PER_PAGE = 100;
    /** GitHub 的 files 接口最多返回 3000 个文件（30 页），作为防止死循环的上限。 */
    static final int MAX_PAGES = 30;

    private final RestClient rest;

    public GitHubPrClient(RestClient.Builder builder, GitHubProperties props) {
        this.rest = builder
                .baseUrl(props.apiBase())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.token())
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                // 所有 4xx/5xx 翻译成带状态码和 GitHub message 的异常（≈ EnsureSuccessStatusCode，但信息更全）
                .defaultStatusHandler(HttpStatusCode::isError, (req, resp) -> {
                    throw GitHubApiException.from(req, resp);
                })
                .build();
    }

    /** GET /repos/{owner}/{repo}/pulls/{n} */
    public PrInfo getPullRequest(ReviewRequest r) {
        PullResponse pull = rest.get()
                .uri("/repos/{owner}/{repo}/pulls/{n}", r.owner(), r.repo(), r.prNumber())
                .retrieve()
                .body(PullResponse.class);
        if (pull == null) {
            throw new GitHubApiException(200, "GitHub 返回了空的 PR 信息: " + r);
        }
        return new PrInfo(pull.title(), pull.head() == null ? null : pull.head().sha());
    }

    /** GET /repos/{owner}/{repo}/pulls/{n}/files，分页拉全，直到返回空页。 */
    public List<PrFile> listFiles(ReviewRequest r) {
        List<PrFile> all = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            PrFile[] batch = rest.get()
                    .uri("/repos/{owner}/{repo}/pulls/{n}/files?per_page={perPage}&page={page}",
                            r.owner(), r.repo(), r.prNumber(), PER_PAGE, page)
                    .retrieve()
                    .body(PrFile[].class);
            if (batch == null || batch.length == 0) {
                return all;
            }
            all.addAll(Arrays.asList(batch));
        }
        log.warn("github_files_cap pr={} pages={} files={} — 已达到 GitHub files 接口上限", r, MAX_PAGES, all.size());
        return all;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PullResponse(String title, Head head) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Head(String sha) {}
}
