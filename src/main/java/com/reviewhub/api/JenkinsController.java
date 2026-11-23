package com.reviewhub.api;

import com.reviewhub.api.dto.ReviewDtos;
import com.reviewhub.domain.ReviewSource;
import com.reviewhub.domain.ReviewTask;
import com.reviewhub.repo.ReviewTaskRepository;
import com.reviewhub.service.SettingsService;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Jenkins 专用端点（可配置 X-API-Key 鉴权）。
 * 供流水线 stage 提交评审、轮询结果、查询质量门禁。
 */
@RestController
@RequestMapping("/api/jenkins")
public class JenkinsController {

    private final ReviewTaskRepository taskRepo;
    private final ReviewController reviewController;
    private final SettingsService settings;

    public JenkinsController(ReviewTaskRepository taskRepo,
                             ReviewController reviewController, SettingsService settings) {
        this.taskRepo = taskRepo;
        this.reviewController = reviewController;
        this.settings = settings;
    }

    /** 提交评审（source 强制为 JENKINS） */
    @PostMapping("/submit")
    public ReviewDtos.SubmitResponse submit(@Valid @RequestBody ReviewDtos.SubmitReviewRequest req) {
        return reviewController.submit(new ReviewDtos.SubmitReviewRequest(
                req.repoName(), req.branch(), req.commitId(), req.author(),
                ReviewSource.JENKINS.name(), req.diff(), req.files(),
                req.jenkinsJob(), req.jenkinsBuild(), req.callbackUrl(),
                req.requirementDocs(), req.designDocs(), req.requirementDocText(), req.designDocText(),
                req.repoUrl()));
    }

    /** 轮询评审状态（轻量，不含 diff） */
    @GetMapping("/reviews/{id}")
    public Map<String, Object> status(@PathVariable Long id) {
        ReviewTask t = taskRepo.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("评审任务不存在: " + id));
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("status", t.getStatus().name());
        m.put("score", t.getScore());
        m.put("summary", t.getSummary());
        m.put("error", t.getError());
        m.put("findings", t.totalFindings());
        m.put("blockers", t.getCntBlocker());
        m.put("criticals", t.getCntCritical());
        m.put("gateSeverity", settings.get("review.gateSeverity", "CRITICAL"));
        return m;
    }

    /** 质量门禁：0=放行 1=阻断（便于脚本判断退出码） */
    @GetMapping("/reviews/{id}/gate")
    public ReviewDtos.GateResponse gate(@PathVariable Long id) {
        return reviewController.gate(id);
    }
}
