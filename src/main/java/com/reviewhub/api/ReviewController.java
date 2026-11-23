package com.reviewhub.api;

import com.reviewhub.api.dto.ReviewDtos;
import com.reviewhub.domain.Finding;
import com.reviewhub.domain.FindingStatus;
import com.reviewhub.domain.ReviewSource;
import com.reviewhub.domain.ReviewStatus;
import com.reviewhub.domain.ReviewTask;
import com.reviewhub.domain.Severity;
import com.reviewhub.engine.DeepScanService;
import com.reviewhub.engine.DocFetcher;
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
    private final DocFetcher docFetcher;
    private final DeepScanService deepScanService;
    private final ObjectMapper om = new ObjectMapper();

    public ReviewController(ReviewTaskRepository taskRepo, FindingRepository findingRepo,
                            ReviewQueueService queue, SettingsService settings,
                            DocFetcher docFetcher, DeepScanService deepScanService) {
        this.taskRepo = taskRepo;
        this.findingRepo = findingRepo;
        this.queue = queue;
        this.settings = settings;
        this.docFetcher = docFetcher;
        this.deepScanService = deepScanService;
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
        task.setRepoUrl(parseRepoUrl(req.repoUrl()));
        task.setRequirementDocs(serializeUrls(sanitizeUrls(req.requirementDocs(), "需求文档链接")));
        task.setDesignDocs(serializeUrls(sanitizeUrls(req.designDocs(), "设计文档链接")));
        task.setRequirementDocText(clipText(req.requirementDocText(), MAX_DOC_TEXT));
        task.setDesignDocText(clipText(req.designDocText(), MAX_DOC_TEXT));
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
                queueDepth, findings,
                t.getRepoUrl(),
                docFetcher.parseUrls(t.getRequirementDocs()), docFetcher.parseUrls(t.getDesignDocs()),
                t.getDocsInfo(),
                t.getDeepScanStatus() == null ? "NONE" : t.getDeepScanStatus().name(),
                t.getDeepScanReport(), t.getDeepScanError(),
                str(t.getDeepScanStartedAt()), str(t.getDeepScanFinishedAt()));
    }

    /** 触发深度扫描：拉取该分支仓库全部代码，做仓库级改动影响分析 */
    @PostMapping("/{id}/deep-scan")
    public ReviewDtos.DeepScanResponse deepScan(@PathVariable Long id,
                                                @RequestBody(required = false) ReviewDtos.DeepScanRequest req) {
        ReviewTask t = taskRepo.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("评审任务不存在: " + id));
        if (t.getStatus() == ReviewStatus.QUEUED || t.getStatus() == ReviewStatus.RUNNING) {
            throw new IllegalStateException("评审尚未完成，请等待评审结束后再深度扫描");
        }
        if (t.getDeepScanStatus() == com.reviewhub.domain.DeepScanStatus.QUEUED
                || t.getDeepScanStatus() == com.reviewhub.domain.DeepScanStatus.RUNNING) {
            throw new IllegalStateException("深度扫描正在进行中，请稍候");
        }
        if (req != null && req.repoUrl() != null && !req.repoUrl().isBlank()) {
            t.setRepoUrl(parseRepoUrl(req.repoUrl()));
        }
        if (req != null && req.branch() != null && !req.branch().isBlank()) {
            t.setBranch(req.branch().trim());
        }
        if (t.getRepoUrl() == null || t.getRepoUrl().isBlank()) {
            throw new IllegalArgumentException("请先提供仓库地址（git clone 用的 http(s) 或 file:// 地址）");
        }
        t.setDeepScanStatus(com.reviewhub.domain.DeepScanStatus.QUEUED);
        t.setDeepScanError(null);
        taskRepo.save(t);
        deepScanService.enqueue(t.getId());
        return new ReviewDtos.DeepScanResponse("QUEUED");
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

    private static final int MAX_DOC_TEXT = 50 * 1024;   // 手动粘贴的文档内容上限（字符）
    private static final int MAX_DOC_URLS = 5;          // 每类文档链接数量上限

    /** 校验链接列表：http/https、去重、限长限量 */
    private static List<String> sanitizeUrls(List<String> urls, String what) {
        if (urls == null || urls.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (String u : urls) {
            if (u == null) continue;
            String v = u.trim();
            if (v.isEmpty()) continue;
            if (!v.startsWith("http://") && !v.startsWith("https://")) {
                throw new IllegalArgumentException(what + "仅支持 http/https 链接：" + clipText(v, 100));
            }
            if (v.length() > 500) {
                throw new IllegalArgumentException(what + "过长（>500 字符）");
            }
            if (!out.contains(v)) out.add(v);
        }
        if (out.size() > MAX_DOC_URLS) {
            throw new IllegalArgumentException(what + "最多 " + MAX_DOC_URLS + " 个");
        }
        return out;
    }

    private static String serializeUrls(List<String> urls) {
        if (urls.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < urls.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(urls.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append(']').toString();
    }

    /** 校验仓库地址：http(s) 或 file:// */
    private static String parseRepoUrl(String s) {
        if (s == null || s.isBlank()) return null;
        String v = s.trim();
        if (!v.startsWith("http://") && !v.startsWith("https://") && !v.startsWith("file://") && !v.startsWith("file:")) {
            throw new IllegalArgumentException("仓库地址仅支持 http(s):// 或 file://（" + clipText(v, 100) + "）");
        }
        if (v.length() > 500) {
            throw new IllegalArgumentException("仓库地址过长（>500 字符）");
        }
        return v;
    }

    private static String clipText(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) + "…（内容过长已截断）" : s;
    }

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
