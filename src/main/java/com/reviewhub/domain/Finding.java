package com.reviewhub.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** 单条评审发现（问题） */
@Entity
@Table(name = "finding")
public class Finding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "task_id", nullable = false)
    private ReviewTask task;

    @Column(nullable = false, length = 500)
    private String filePath;

    /** 新文件侧行号 */
    @Column(name = "line_no")
    private Integer lineNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Severity severity = Severity.MAJOR;

    /** 命中的评审规则名 */
    @Column(length = 200)
    private String rule;

    @Column(nullable = false, length = 2000)
    private String message;

    @Lob
    @Column(columnDefinition = "CLOB")
    private String suggestion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FindingStatus status = FindingStatus.PENDING;

    /** 人工备注 */
    @Column(length = 2000)
    private String remark;

    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime updatedAt = LocalDateTime.now();

    // ---- getters / setters ----

    public Long getId() { return id; }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public ReviewTask getTask() { return task; }
    public void setTask(ReviewTask task) { this.task = task; }

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    public Integer getLineNo() { return lineNo; }
    public void setLineNo(Integer lineNo) { this.lineNo = lineNo; }

    public Severity getSeverity() { return severity; }
    public void setSeverity(Severity severity) { this.severity = severity; }

    public String getRule() { return rule; }
    public void setRule(String rule) { this.rule = rule; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getSuggestion() { return suggestion; }
    public void setSuggestion(String suggestion) { this.suggestion = suggestion; }

    public FindingStatus getStatus() { return status; }
    public void setStatus(FindingStatus status) { this.status = status; }

    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
