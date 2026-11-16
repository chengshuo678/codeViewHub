package com.reviewhub.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reviewhub.domain.Finding;
import com.reviewhub.domain.ReviewStatus;
import com.reviewhub.domain.ReviewTask;
import com.reviewhub.repo.FindingRepository;
import com.reviewhub.repo.ReviewTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 异步评审队列：
 *  - 提交后立即返回，任务进入内存队列，由固定大小 worker 池消费（并发数 review.workers）
 *  - 服务重启后自动恢复未完成任务
 *  - 完成后若配置了 callbackUrl，回调整理结果（供 Jenkins 等系统接收）
 */
@Service
public class ReviewQueueService {

    private static final Logger log = LoggerFactory.getLogger(ReviewQueueService.class);

    private final ReviewTaskRepository taskRepo;
    private final FindingRepository findingRepo;
    private final ReviewEngine engine;
    private final ObjectMapper om = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private final int workers;
    private ThreadPoolExecutor pool;

    public ReviewQueueService(ReviewTaskRepository taskRepo,
                              FindingRepository findingRepo,
                              ReviewEngine engine,
                              @Value("${review.workers:2}") int workers) {
        this.taskRepo = taskRepo;
        this.findingRepo = findingRepo;
        this.engine = engine;
        this.workers = Math.max(1, workers);
    }

    @PostConstruct
    public void init() {
        pool = new ThreadPoolExecutor(workers, workers, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), r -> {
            Thread t = new Thread(r, "review-worker");
            t.setDaemon(true);
            return t;
        });
        // 崩溃恢复：重新入队未完成任务
        List<ReviewTask> recoverable = taskRepo.findByStatusIn(List.of(ReviewStatus.QUEUED, ReviewStatus.RUNNING));
        for (ReviewTask t : recoverable) {
            t.setStatus(ReviewStatus.QUEUED);
            taskRepo.save(t);
            enqueue(t.getId());
        }
        if (!recoverable.isEmpty()) {
            log.info("已恢复 {} 个未完成评审任务", recoverable.size());
        }
    }

    @PreDestroy
    public void shutdown() {
        if (pool != null) pool.shutdown();
    }

    /** 提交评审任务到队列 */
    public void enqueue(Long taskId) {
        pool.execute(() -> runOne(taskId));
    }

    /** 队列中等待的任务数（不含正在执行的） */
    public int queuedCount() {
        return pool == null ? 0 : pool.getQueue().size();
    }

    /** 正在评审中的任务数 */
    public int activeCount() {
        return pool == null ? 0 : pool.getActiveCount();
    }

    private void runOne(Long taskId) {
        ReviewTask task = taskRepo.findById(taskId).orElse(null);
        if (task == null) return;
        if (task.getStatus() != ReviewStatus.QUEUED) return; // 已取消或已完成

        task.setStatus(ReviewStatus.RUNNING);
        task.setStartedAt(LocalDateTime.now());
        task.setError(null);
        task = taskRepo.save(task);

        try {
            ReviewEngine.EngineResult result = engine.review(task);
            findingRepo.deleteByTaskId(task.getId());
            int b = 0, c = 0, m = 0, n = 0, i = 0;
            for (ReviewEngine.RawFinding rf : result.findings()) {
                Finding f = new Finding();
                f.setTask(task);
                f.setFilePath(rf.file());
                f.setLineNo(rf.line());
                f.setSeverity(rf.severity());
                f.setRule(rf.rule());
                f.setMessage(rf.message());
                f.setSuggestion(rf.suggestion());
                findingRepo.save(f);
                switch (rf.severity()) {
                    case BLOCKER -> b++;
                    case CRITICAL -> c++;
                    case MAJOR -> m++;
                    case MINOR -> n++;
                    default -> i++;
                }
            }
            task.setCntBlocker(b);
            task.setCntCritical(c);
            task.setCntMajor(m);
            task.setCntMinor(n);
            task.setCntInfo(i);
            task.setScore(result.score());
            task.setSummary(result.summary());
            task.setStatus(ReviewStatus.SUCCESS);
            log.info("评审完成 task={} findings={} score={}", taskId, result.findings().size(), result.score());
        } catch (Exception e) {
            log.error("评审失败 task={}", taskId, e);
            task.setError(clip(e.getMessage(), 1900));
            task.setStatus(ReviewStatus.FAILED);
        }
        task.setFinishedAt(LocalDateTime.now());
        task = taskRepo.save(task);
        fireCallback(task);
    }

    private void fireCallback(ReviewTask task) {
        String url = task.getCallbackUrl();
        if (url == null || url.isBlank()) return;
        try {
            ObjectNode body = om.createObjectNode();
            body.put("id", task.getId());
            body.put("status", task.getStatus().name());
            body.put("score", task.getScore());
            body.put("summary", task.getSummary());
            body.put("findings", task.totalFindings());
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(om.writeValueAsString(body)))
                    .build();
            http.send(req, HttpResponse.BodyHandlers.discarding());
            log.info("评审结果回调成功 task={} url={}", task.getId(), url);
        } catch (Exception e) {
            log.warn("评审结果回调失败 task={} url={} err={}", task.getId(), url, e.getMessage());
        }
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) : s;
    }
}
