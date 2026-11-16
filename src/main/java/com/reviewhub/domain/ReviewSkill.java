package com.reviewhub.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** 评审技能（智能体人格）：一组可复用的系统提示词 */
@Entity
@Table(name = "review_skill")
public class ReviewSkill {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    /** 系统提示词，支持变量：{{repo}} {{branch}} */
    @Lob
    @Column(columnDefinition = "CLOB", nullable = false)
    private String systemPrompt;

    @Column(nullable = false)
    private boolean enabled = true;

    /** 是否为默认技能（每次评审使用） */
    @Column(nullable = false)
    private boolean defaulted = false;

    @Column(nullable = false)
    private boolean builtin = false;

    private LocalDateTime createdAt = LocalDateTime.now();

    // ---- getters / setters ----

    public Long getId() { return id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean isDefaulted() { return defaulted; }
    public void setDefaulted(boolean defaulted) { this.defaulted = defaulted; }

    public boolean isBuiltin() { return builtin; }
    public void setBuiltin(boolean builtin) { this.builtin = builtin; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
