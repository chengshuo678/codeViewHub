package com.reviewhub.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** 评审规则：决定哪些文件被评审、以什么严格程度评审 */
@Entity
@Table(name = "review_rule")
public class ReviewRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    /** 文件匹配模式：Ant 通配符，逗号分隔多个，例如 "星号星号斜杠星号.java" 表示所有 Java 文件 */
    @Column(name = "file_pattern", length = 500)
    private String filePattern;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Severity severity = Severity.MAJOR;

    /** 给 LLM 的补充提示（该规则关注什么） */
    @Column(length = 3000)
    private String prompt;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = false)
    private boolean builtin = false;

    private LocalDateTime createdAt = LocalDateTime.now();

    // ---- getters / setters ----

    public Long getId() { return id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getFilePattern() { return filePattern; }
    public void setFilePattern(String filePattern) { this.filePattern = filePattern; }

    public Severity getSeverity() { return severity; }
    public void setSeverity(Severity severity) { this.severity = severity; }

    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean isBuiltin() { return builtin; }
    public void setBuiltin(boolean builtin) { this.builtin = builtin; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
