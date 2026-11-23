package com.reviewhub.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

/** 评审相关 DTO */
public final class ReviewDtos {

    private ReviewDtos() {
    }

    public record SubmitReviewRequest(
            @NotBlank(message = "repoName 不能为空") @Size(max = 200) String repoName,
            @Size(max = 200) String branch,
            @Size(max = 80) String commitId,
            @Size(max = 100) String author,
            @Size(max = 20) String source,
            String diff,
            Map<String, String> files,
            @Size(max = 200) String jenkinsJob,
            @Size(max = 50) String jenkinsBuild,
            @Size(max = 500) String callbackUrl,
            java.util.List<String> requirementDocs,
            java.util.List<String> designDocs,
            String requirementDocText,
            String designDocText,
            @Size(max = 500) String repoUrl) {
    }

    public record SubmitResponse(Long id, String status) {
    }

    public record FindingDto(Long id, String filePath, Integer lineNo, String severity, String severityLabel,
                             String rule, String message, String suggestion, String status, String statusLabel,
                             String remark, String updatedAt) {
    }

    public record ReviewDetail(
            Long id, String repoName, String branch, String commitId, String author, String source,
            String status, Integer score, String summary, String error,
            String jenkinsJob, String jenkinsBuild,
            String diffText,
            Integer cntBlocker, Integer cntCritical, Integer cntMajor, Integer cntMinor, Integer cntInfo,
            String createdAt, String startedAt, String finishedAt,
            Integer queueDepth,
            java.util.List<FindingDto> findings,
            String repoUrl,
            java.util.List<String> requirementDocs, java.util.List<String> designDocs,
            String docsInfo,
            String deepScanStatus, String deepScanReport, String deepScanError,
            String deepScanStartedAt, String deepScanFinishedAt) {
    }

    /** 深度扫描触发请求（repoUrl 缺省时用于补填） */
    public record DeepScanRequest(
            @Size(max = 500) String repoUrl,
            @Size(max = 200) String branch) {
    }

    /** 深度扫描触发响应 */
    public record DeepScanResponse(String deepScanStatus) {
    }

    public record ReviewListItem(
            Long id, String repoName, String branch, String commitId, String author, String source,
            String status, Integer score, String summary,
            Integer cntBlocker, Integer cntCritical, Integer cntMajor, Integer cntMinor, Integer cntInfo,
            Integer totalFindings,
            String jenkinsJob, String jenkinsBuild,
            String createdAt, String finishedAt) {
    }

    public record FindingUpdateRequest(String status, String remark) {
    }

    public record GateResponse(boolean pass, String reason, int blockers, int criticals, int total) {
    }

    public record StatsResponse(long totalReviews, long completed, long running, long failed,
                                long totalFindings, long confirmedFindings,
                                java.util.Map<String, Long> bySeverity,
                                int queueDepth, int activeWorkers) {
    }

    /** 稳定结构的分页响应（避免直接序列化 PageImpl） */
    public record PageDto<T>(java.util.List<T> content, long totalElements, int totalPages, int number) {
    }
}
