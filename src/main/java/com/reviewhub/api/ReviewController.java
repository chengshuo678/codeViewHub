package com.reviewhub.api;

import com.reviewhub.api.dto.ReviewDtos;
import com.reviewhub.domain.Finding;
import com.reviewhub.domain.FindingStatus;
import com.reviewhub.domain.ReviewSource;
import com.reviewhub.domain.ReviewStatus;
import com.reviewhub.domain.ReviewTask;
import com.reviewhub.domain.Severity;
import com.reviewhub.engine.ReviewQueueService;
import com.reviewhub.repo.FindingRepository;
import com.reviewhub.repo.ReviewTaskRepository;
import com.reviewhub.service.SettingsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 评审任务 API（Web 页面使用） */
@RestController
@RequestMapping("/api/reviews")
public class ReviewController {

    private static final int MAX_DIFF_SIZE = 4 * 1024 * 1024;      // 4MB
    private static final int MAX_BUNDLE_SIZE = 16 * 1024 * 1024;   // 16MB

    private final ReviewTaskRepository taskRepo;
    private final FindingRepository findingRepo;
    private final ReviewQueueService queue;
    private final SettingsService settings;
    private final ObjectMapper om = new ObjectMapper();

    public ReviewController(ReviewTaskRepository taskRepo, FindingRepository findingRepo,
                            ReviewQueueService queue, SettingsService settings) {
        this.taskRepo = taskRepo;
        this.findingRepo = findingRepo;
        this.queue = queue;
        this.settings = settings;
    }

    /** 提交评审（异步：立即入队并返回） */
    @PostMapping
    public ReviewDtos.SubmitResponse submit(@Valid @RequestBody ReviewDtos.SubmitReviewRequest req) {
        String diff = req.diff() == null ? "" : req.diff();
        if (diff.length() > MAX_DIFF_SIZE) {
            throw new IllegalArgumentException("diff 过大（" + diff.length() / 1024 + "KB），请控制在 4MB 以内");
        }
        String bundle = serializeFiles(req.files());
        if (bundle != null && bundle.length() > MAX_BUNDLE_SIZE) {
            throw new IllegalArgumentException("文件内容包过大，请控制在 16MB 以内");
        }

        ReviewTask task = new ReviewTask();
        task.setRepoName(req.repoName().trim());
        task.setBranch(trim(req.branch()));
        task.setCommitId(trim(req.commitId()));
        task.setAuthor(trim(req.author()));
        task.setSource(parseSource(req.source()));
        task.setDiffText(diff);
        task.setFilesBundle(bundle);
        task.setJenkinsJob(trim(req.jenkinsJob()));
        task.setJenkinsBuild(trim(req.jenkinsBuild()));
        task.setCallbackUrl(trim(req.callbackUrl()));
        task.setStatus(ReviewStatus.QUEUED);
        task = taskRepo.save(task);

        queue.enqueue(task.getId());
        return new ReviewDtos.SubmitResponse(task.getId(), task.getStatus().name());
    }

    /** 评审任务分页列表 */
    @GetMapping
    public ReviewDtos.PageDto<ReviewDtos.ReviewListItem> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "15") int size) {
        Specification<ReviewTask> spec = (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> ps = new ArrayList<>();
            if (status != null && !status.isBlank()) {
                ps.add(cb.equal(root.get("status"), ReviewStatus.valueOf(status)));
            }
            if (q != null && !q.isBlank()) {
                String like = "%" + q.trim().toLowerCase() + "%";
                ps.add(cb.or(
                        cb.like(cb.lower(root.get("repoName")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("branch"), "")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("author"), "")), like)));
            }
            return cb.and(ps.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
        Page<ReviewTask> result = taskRepo.findAll(spec,
                PageRequest.of(Math.max(0, page), Math.min(100, Math.max(5, size)), Sort.by(Sort.Direction.DESC, "id")));
        Page<ReviewDtos.ReviewListItem> mapped = result.map(this::toListDto);
        return new ReviewDtos.PageDto<>(mapped.getContent(), mapped.getTotalElements(),
                mapped.getTotalPages(), mapped.getNumber());
    }

    /** 评审详情（含 diff 与全部发现） */
    @GetMapping("/{id}")
    public ReviewDtos.ReviewDetail detail(@PathVariable Long id) {
        ReviewTask t = taskRepo.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("评审任务不存在: " + id));
        List<ReviewDtos.FindingDto> findings = findingRepo
                .findByTaskIdOrderByFilePathAscLineNoAscIdAsc(id)
                .stream().map(this::toFindingDto).toList();
        Integer queueDepth = t.getStatus() == ReviewStatus.QUEUED ? queue.queuedCount() : 0;
        return new ReviewDtos.ReviewDetail(
                t.getId(), t.getRepoName(), t.getBranch(), t.getCommitId(), t.getAuthor(),
                t.getSource().name(), t.getStatus().name(), t.getScore(), t.getSummary(), t.getError(),
                t.getJenkinsJob(), t.getJenkinsBuild(), t.getDiffText(),
                t.getCntBlocker(), t.getCntCritical(), t.getCntMajor(), t.getCntMinor(), t.getCntInfo(),
                str(t.getCreatedAt()), str(t.getStartedAt()), str(t.getFinishedAt()),
                queueDepth, findings);
    }

    /** 更新发现的状态 / 备注 */
    @PutMapping("/{id}/findings/{fid}")
    public ReviewDtos.FindingDto updateFinding(@PathVariable Long id, @PathVariable Long fid,
                                               @RequestBody ReviewDtos.FindingUpdateRequest req) {
        Finding f = findingRepo.findById(fid)
                .orElseThrow(() -> new EntityNotFoundException("评审发现不存在: " + fid));
        if (f.getTask() == null || !id.equals(f.getTask().getId())) {
            throw new IllegalArgumentException("该发现不属于评审任务 " + id);
        }
        if (req.status() != null && !req.status().isBlank()) {
            f.setStatus(FindingStatus.valueOf(req.status().trim().toUpperCase()));
        }
        if (req.remark() != null) {
            f.setRemark(trim(req.remark()));
        }
        f.setUpdatedAt(java.time.LocalDateTime.now());
        return toFindingDto(findingRepo.save(f));
    }

    /** 质量门禁：是否放行流水线 */
    @GetMapping("/{id}/gate")
    public ReviewDtos.GateResponse gate(@PathVariable Long id) {
        ReviewTask t = taskRepo.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("评审任务不存在: " + id));
        if (t.getStatus() == ReviewStatus.QUEUED || t.getStatus() == ReviewStatus.RUNNING) {
            throw new IllegalStateException("评审尚未完成，当前状态: " + t.getStatus());
        }
        if (t.getStatus() == ReviewStatus.FAILED) {
            return new ReviewDtos.GateResponse(true, "评审执行失败（不阻断流水线）", 0, 0, 0);
        }
        Severity gate = Severity.parse(settings.get("review.gateSeverity", "CRITICAL"), Severity.CRITICAL);
        int blockers = n(t.getCntBlocker());
        int criticals = n(t.getCntCritical());
        boolean pass = true;
        if (gate.ordinal() <= Severity.CRITICAL.ordinal()) pass = blockers == 0 && criticals == 0;
        if (gate.ordinal() <= Severity.BLOCKER.ordinal()) pass = blockers == 0;
        String reason = pass
                ? "通过：无 " + gate.getLabel() + " 及以上问题"
                : "不通过：存在 " + blockers + " 个阻断、" + criticals + " 个严重问题（阈值：" + gate.getLabel() + "）";
        return new ReviewDtos.GateResponse(pass, reason, blockers, criticals, t.totalFindings());
    }

    /** 失败重试 */
    @PostMapping("/{id}/retry")
    public ReviewDtos.SubmitResponse retry(@PathVariable Long id) {
        ReviewTask t = taskRepo.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("评审任务不存在: " + id));
        if (t.getStatus() == ReviewStatus.QUEUED || t.getStatus() == ReviewStatus.RUNNING) {
            throw new IllegalStateException("任务正在排队/评审中，无法重试");
        }
        t.setStatus(ReviewStatus.QUEUED);
        t.setError(null);
        t.setSummary(null);
        t.setScore(null);
        t.setStartedAt(null);
        t.setFinishedAt(null);
        t = taskRepo.save(t);
        queue.enqueue(t.getId());
        return new ReviewDtos.SubmitResponse(t.getId(), t.getStatus().name());
    }

    /** 仪表盘统计 */
    @GetMapping("/stats")
    public ReviewDtos.StatsResponse stats() {
        Map<ReviewStatus, Long> byStatus = new HashMap<>();
        taskRepo.countGroupByStatus().forEach(o -> byStatus.put((ReviewStatus) o[0], (Long) o[1]));
        Map<String, Long> bySeverity = new HashMap<>();
        findingRepo.countGroupBySeverity().forEach(o -> bySeverity.put(((Severity) o[0]).name(), (Long) o[1]));
        long completed = byStatus.getOrDefault(ReviewStatus.SUCCESS, 0L);
        long total = taskRepo.count();
        return new ReviewDtos.StatsResponse(
                total,
                completed,
                byStatus.getOrDefault(ReviewStatus.QUEUED, 0L) + byStatus.getOrDefault(ReviewStatus.RUNNING, 0L),
                byStatus.getOrDefault(ReviewStatus.FAILED, 0L),
                findingRepo.count(),
                findingRepo.countByStatus(FindingStatus.CONFIRMED),
                bySeverity,
                queue.queuedCount(),
                queue.activeCount());
    }

    // ------------------------------------------------------------------

    private String serializeFiles(Map<String, String> files) {
        if (files == null || files.isEmpty()) return null;
        try {
            return om.writeValueAsString(files);
        } catch (Exception e) {
            throw new IllegalArgumentException("文件内容包序列化失败: " + e.getMessage());
        }
    }

    private ReviewSource parseSource(String s) {
        if (s == null || s.isBlank()) return ReviewSource.MANUAL;
        try {
            return ReviewSource.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ReviewSource.MANUAL;
        }
    }

    private ReviewDtos.ReviewListItem toListDto(ReviewTask t) {
        return new ReviewDtos.ReviewListItem(
                t.getId(), t.getRepoName(), t.getBranch(), t.getCommitId(), t.getAuthor(),
                t.getSource().name(), t.getStatus().name(), t.getScore(), t.getSummary(),
                t.getCntBlocker(), t.getCntCritical(), t.getCntMajor(), t.getCntMinor(), t.getCntInfo(),
                t.totalFindings(), t.getJenkinsJob(), t.getJenkinsBuild(),
                str(t.getCreatedAt()), str(t.getFinishedAt()));
    }

    private ReviewDtos.FindingDto toFindingDto(Finding f) {
        return new ReviewDtos.FindingDto(
                f.getId(), f.getFilePath(), f.getLineNo(),
                f.getSeverity().name(), f.getSeverity().getLabel(),
                f.getRule(), f.getMessage(), f.getSuggestion(),
                f.getStatus().name(), f.getStatus().getLabel(),
                f.getRemark(), str(f.getUpdatedAt()));
    }

    private static int n(Integer v) {
        return v == null ? 0 : v;
    }

    private static String str(java.time.LocalDateTime t) {
        return t == null ? null : t.toString();
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }
}
