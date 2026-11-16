package com.reviewhub.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 规则 / 技能 / 设置 DTO */
public final class AdminDtos {

    private AdminDtos() {
    }

    public record RuleRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 500) String description,
            @NotBlank @Size(max = 500) String filePattern,
            @NotBlank @Size(max = 20) String severity,
            @Size(max = 3000) String prompt,
            Boolean enabled) {
    }

    public record SkillRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 500) String description,
            @NotBlank String systemPrompt,
            Boolean enabled) {
    }

    public record SettingsUpdate(
            String provider,
            String baseUrl,
            String apiKey,
            String model,
            String temperature,
            Integer maxRounds,
            Integer groupMaxLines,
            Boolean reflection,
            String gateSeverity,
            String hubApiKey) {
    }

    public record SettingsView(
            String provider, String baseUrl, String model, String temperature,
            boolean apiKeySet,
            int maxRounds, int groupMaxLines, boolean reflection, String gateSeverity,
            boolean hubApiKeySet,
            String hubVersion) {
    }

    public record TestLlmResponse(boolean ok, long latencyMs, String reply, String error) {
    }
}
