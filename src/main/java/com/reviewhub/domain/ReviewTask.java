package com.reviewhub.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** 评审任务 */
@Entity
@Table(name = "review_task")
public class ReviewTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String repoName;

    @Column(length = 200)
    private String branch;

    @Column(length = 80)
    private String commitId;

    @Column(length = 100)
    private String author;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReviewSource source = ReviewSource.MANUAL;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReviewStatus status = ReviewStatus.QUEUED;

    /** LLM 生成的一句话总结 */
    @Column(length = 1000)
    private String summary;

    /** LLM 评分 0-100 */
    private Integer score;

    /** 原始 diff 文本 */
    @Lob
    @Column(columnDefinition = "CLOB")
    private String diffText;

    /** 可选的文件内容包 JSON：{"path": "content"}，供 Agent 工具读取上下文 */
    @Lob
    @Column(columnDefinition = "CLOB")
    private String filesBundle;

    @Column(length = 2000)
    private String error;

    @Column(length = 200)
    private String jenkinsJob;

    @Column(length = 50)
    private String jenkinsBuild;

    @Column(length = 500)
    private String callbackUrl;

    // ---- 完成时回填的统计（按严重级别的问题数） ----
    private Integer cntBlocker = 0;
    private Integer cntCritical = 0;
    private Integer cntMajor = 0;
    private Integer cntMinor = 0;
    private Integer cntInfo = 0;

    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    public int totalFindings() {
        return n(cntBlocker) + n(cntCritical) + n(cntMajor) + n(cntMinor) + n(cntInfo);
    }

    private static int n(Integer v) {
        return v == null ? 0 : v;
    }

    // ---- getters / setters ----

    public Long getId() { return id; }

    public String getRepoName() { return repoName; }
    public void setRepoName(String repoName) { this.repoName = repoName; }

    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }

    public String getCommitId() { return commitId; }
    public void setCommitId(String commitId) { this.commitId = commitId; }

    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }

    public ReviewSource getSource() { return source; }
    public void setSource(ReviewSource source) { this.source = source; }

    public ReviewStatus getStatus() { return status; }
    public void setStatus(ReviewStatus status) { this.status = status; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public Integer getScore() { return score; }
    public void setScore(Integer score) { this.score = score; }

    public String getDiffText() { return diffText; }
    public void setDiffText(String diffText) { this.diffText = diffText; }

    public String getFilesBundle() { return filesBundle; }
    public void setFilesBundle(String filesBundle) { this.filesBundle = filesBundle; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public String getJenkinsJob() { return jenkinsJob; }
    public void setJenkinsJob(String jenkinsJob) { this.jenkinsJob = jenkinsJob; }

    public String getJenkinsBuild() { return jenkinsBuild; }
    public void setJenkinsBuild(String jenkinsBuild) { this.jenkinsBuild = jenkinsBuild; }

    public String getCallbackUrl() { return callbackUrl; }
    public void setCallbackUrl(String callbackUrl) { this.callbackUrl = callbackUrl; }

    public Integer getCntBlocker() { return cntBlocker; }
    public void setCntBlocker(Integer cntBlocker) { this.cntBlocker = cntBlocker; }

    public Integer getCntCritical() { return cntCritical; }
    public void setCntCritical(Integer cntCritical) { this.cntCritical = cntCritical; }

    public Integer getCntMajor() { return cntMajor; }
    public void setCntMajor(Integer cntMajor) { this.cntMajor = cntMajor; }

    public Integer getCntMinor() { return cntMinor; }
    public void setCntMinor(Integer cntMinor) { this.cntMinor = cntMinor; }

    public Integer getCntInfo() { return cntInfo; }
    public void setCntInfo(Integer cntInfo) { this.cntInfo = cntInfo; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }

    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
}
