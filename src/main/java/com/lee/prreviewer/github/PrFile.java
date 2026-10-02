package com.lee.prreviewer.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * GitHub GET /pulls/{n}/files 返回的单个文件（只取需要的字段）。
 * C# 对照：@JsonProperty ≈ [JsonPropertyName]；ignoreUnknown ≈ System.Text.Json 默认忽略未知字段。
 *
 * @param patch 可能为 null：二进制文件、改动过大时 GitHub 不返回
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PrFile(
        String filename,
        String status,
        String patch,
        int changes,
        @JsonProperty("previous_filename") String previousFilename
) {}
