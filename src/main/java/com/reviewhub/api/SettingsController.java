package com.reviewhub.api;

import com.reviewhub.api.dto.AdminDtos;
import com.reviewhub.domain.Severity;
import com.reviewhub.engine.llm.LlmClient;
import com.reviewhub.engine.llm.LlmModels;
import com.reviewhub.service.SettingsService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 系统设置 API */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final SettingsService settings;
    private final LlmClient llm;

    public SettingsController(SettingsService settings, LlmClient llm) {
        this.settings = settings;
        this.llm = llm;
    }

    @GetMapping
    public AdminDtos.SettingsView view() {
        return new AdminDtos.SettingsView(
                settings.get("llm.provider", "mock"),
                settings.get("llm.baseUrl"),
                settings.get("llm.model"),
                settings.get("llm.temperature", "0.1"),
                !settings.get("llm.apiKey").isBlank(),
                settings.getInt("review.maxRounds", 6),
                settings.getInt("review.groupMaxLines", 600),
                settings.getBool("review.reflection", true),
                settings.get("review.gateSeverity", "CRITICAL"),
                !settings.get("security.apiKey").isBlank(),
                "1.0.0");
    }

    @PutMapping
    public AdminDtos.SettingsView update(@RequestBody AdminDtos.SettingsUpdate req) {
        if (req.provider() != null) {
            String p = req.provider().trim().toLowerCase();
            if (!p.equals("openai") && !p.equals("mock")) {
                throw new IllegalArgumentException("provider 仅支持 openai / mock");
            }
            settings.put("llm.provider", p);
        }
        if (req.baseUrl() != null) settings.put("llm.baseUrl", req.baseUrl().trim());
        if (req.model() != null) settings.put("llm.model", req.model().trim());
        if (req.temperature() != null) settings.put("llm.temperature", req.temperature().trim());
        if (req.apiKey() != null && !req.apiKey().isBlank()) settings.put("llm.apiKey", req.apiKey().trim());
        if (req.maxRounds() != null) settings.put("review.maxRounds", String.valueOf(clamp(req.maxRounds(), 1, 20)));
        if (req.groupMaxLines() != null) settings.put("review.groupMaxLines", String.valueOf(clamp(req.groupMaxLines(), 100, 5000)));
        if (req.reflection() != null) settings.put("review.reflection", String.valueOf(req.reflection()));
        if (req.gateSeverity() != null) {
            Severity gs = Severity.parse(req.gateSeverity(), null);
            if (gs == null) {
                throw new IllegalArgumentException("无效的严重级别: " + req.gateSeverity());
            }
            settings.put("review.gateSeverity", gs.name());
        }
        if (req.hubApiKey() != null && !req.hubApiKey().isBlank()) settings.put("security.apiKey", req.hubApiKey().trim());
        return view();
    }

    /** 清空接入密钥 */
    @DeleteMapping("/hub-api-key")
    public AdminDtos.SettingsView clearHubApiKey() {
        settings.put("security.apiKey", "");
        return view();
    }

    /** 清空模型密钥 */
    @DeleteMapping("/llm-api-key")
    public AdminDtos.SettingsView clearLlmApiKey() {
        settings.put("llm.apiKey", "");
        return view();
    }

    /** 模型连通性测试 */
    @PostMapping("/test-llm")
    public AdminDtos.TestLlmResponse testLlm() {
        long start = System.currentTimeMillis();
        try {
            LlmModels.LlmResponse resp = llm.chat(
                    List.of(LlmModels.LlmMessage.user("连通性测试：请只回复 pong")),
                    null);
            long cost = System.currentTimeMillis() - start;
            String reply = resp.content == null ? "(工具调用)" : resp.content;
            return new AdminDtos.TestLlmResponse(true, cost, clip(reply, 200), null);
        } catch (Exception e) {
            return new AdminDtos.TestLlmResponse(false, System.currentTimeMillis() - start, null, e.getMessage());
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static String clip(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
