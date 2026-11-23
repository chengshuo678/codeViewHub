package com.reviewhub.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reviewhub.domain.DeepScanStatus;
import com.reviewhub.domain.ReviewTask;
import com.reviewhub.engine.diff.DiffFile;
import com.reviewhub.engine.diff.DiffParser;
import com.reviewhub.engine.llm.LlmClient;
import com.reviewhub.engine.llm.LlmModels;
import com.reviewhub.repo.ReviewTaskRepository;
import com.reviewhub.service.SettingsService;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 深度扫描：拉取该分支仓库的全部代码（内置纯 Java Git 客户端克隆，无需平台安装 git），
 * 结合提交 diff 做仓库级改动影响分析：
 *   1. 静态分析：从 diff 提取变更符号（类/方法/函数/常量等标识符），在全仓反向查找引用；
 *   2. LLM 综合：把变更符号、受影响文件与引用证据交给大模型，产出影响报告
 *      （受影响模块 / 隐藏风险 / 回归测试建议）。
 */
@Service
public class DeepScanService {

    private static final Logger log = LoggerFactory.getLogger(DeepScanService.class);

    // ---- 扫描规模上限（防大仓拖垮内存/耗时） ----
    private static final int MAX_FILES = 5000;
    private static final int MAX_FILE_BYTES = 512 * 1024;
    private static final int SYMBOLS_PER_FILE = 12;
    private static final int MAX_SYMBOLS = 60;
    private static final int MAX_AFFECTED = 50;
    private static final int MAX_EVIDENCE_FILES = 25;
    private static final int PROMPT_CAP = 40000;

    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", "node_modules", "target", "build", "dist", "out", ".idea", ".vscode",
            "vendor", "coverage", ".gradle", "logs", "log", "tmp", "__pycache__");

    private static final Set<String> CODE_EXTS = Set.of(
            "java", "js", "mjs", "cjs", "ts", "tsx", "jsx", "vue", "py", "go", "rs",
            "c", "h", "cc", "cpp", "hpp", "kt", "kts", "swift", "php", "rb", "cs",
            "scala", "sql", "sh", "bat", "ps1", "xml", "yml", "yaml", "json", "properties", "html", "css");

    /** 语言关键字与过于宽泛的标识符（不作为反向查找的符号） */
    private static final Set<String> KEYWORDS = Set.of(
            "return", "import", "package", "class", "interface", "enum", "struct", "public",
            "private", "protected", "static", "final", "void", "extends", "implements",
            "function", "func", "const", "let", "var", "this", "self", "super", "new",
            "if", "else", "for", "while", "switch", "case", "break", "continue", "try",
            "catch", "finally", "throw", "throws", "async", "await", "yield", "from",
            "require", "export", "default", "true", "false", "null", "undefined", "nil",
            "type", "def", "lambda", "pass", "with", "as", "not", "and", "or",
            "test", "tests", "main", "int", "long", "double", "float", "boolean", "char",
            "byte", "short", "object", "number", "list", "map", "set", "array",
            "error", "exception", "data", "value", "name", "params", "args", "result",
            "context", "config", "item", "items", "text", "size", "count", "index",
            "length", "string", "json", "xml", "http", "url");

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]{2,}");

    private final ReviewTaskRepository taskRepo;
    private final SettingsService settings;
    private final LlmClient llm;
    private final DiffParser diffParser;
    private final ObjectMapper om = new ObjectMapper();

    private ThreadPoolExecutor pool;

    public DeepScanService(ReviewTaskRepository taskRepo, SettingsService settings,
                           LlmClient llm, DiffParser diffParser) {
        this.taskRepo = taskRepo;
        this.settings = settings;
        this.llm = llm;
        this.diffParser = diffParser;
    }

    @PostConstruct
    public void init() {
        pool = new ThreadPoolExecutor(1, 1, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
            Thread t = new Thread(r, "deep-scan-worker");
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    public void shutdown() {
        if (pool != null) pool.shutdown();
    }

    /** 提交深度扫描（异步执行） */
    public void enqueue(Long taskId) {
        pool.execute(() -> runOne(taskId));
    }

    // ------------------------------------------------------------------
    // 执行
    // ------------------------------------------------------------------

    private void runOne(Long taskId) {
        ReviewTask task = taskRepo.findById(taskId).orElse(null);
        if (task == null) return;
        if (task.getDeepScanStatus() != DeepScanStatus.QUEUED) return;

        task.setDeepScanStatus(DeepScanStatus.RUNNING);
        task.setDeepScanStartedAt(LocalDateTime.now());
        task.setDeepScanError(null);
        task = taskRepo.save(task);
        log.info("深度扫描开始 task={} repo={}", taskId, task.getRepoUrl());

        Path dir = null;
        try {
            dir = Files.createTempDirectory("crh-deepscan-");
            cloneRepo(task.getRepoUrl(), task.getBranch(), dir);
            Map<String, Object> report = analyze(task, dir);
            task.setDeepScanReport(om.writeValueAsString(report));
            task.setDeepScanStatus(DeepScanStatus.SUCCESS);
            log.info("深度扫描完成 task={} changedFiles={} affected={}",
                    taskId, report.get("changedFiles"),
                    report.get("static") instanceof Map<?, ?> m ? m.get("affectedCount") : "?");
        } catch (Exception e) {
            log.error("深度扫描失败 task={}", taskId, e);
            task.setDeepScanStatus(DeepScanStatus.FAILED);
            task.setDeepScanError(clip(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), 1900));
        } finally {
            task.setDeepScanFinishedAt(LocalDateTime.now());
            taskRepo.save(task);
            if (dir != null) deleteRecursively(dir);
        }
    }

    /** 克隆仓库指定分支到临时目录（浅克隆；file:// 本地源不支持 depth） */
    private void cloneRepo(String url, String branch, Path dir) throws Exception {
        CloneCommand cmd = Git.cloneRepository()
                .setURI(url)
                .setDirectory(dir.toFile());
        if (!url.startsWith("file:")) {
            cmd.setDepth(1); // http(s) 智能协议支持浅克隆，减少传输量
        }
        if (branch != null && !branch.isBlank()) {
            String ref = branch.startsWith("refs/") ? branch : "refs/heads/" + branch;
            cmd.setBranchesToClone(List.of(ref)).setBranch(ref);
        }
        String user = settings.get("git.user");
        String password = settings.get("git.password");
        if (!user.isBlank() && !password.isBlank()) {
            cmd.setCredentialsProvider(new UsernamePasswordCredentialsProvider(user, password));
        }
        try (Git git = cmd.call()) {
            log.info("仓库克隆成功：{}（branch={}）", sanitize(url), branch);
        }
    }

    // ------------------------------------------------------------------
    // 影响分析
    // ------------------------------------------------------------------

    private Map<String, Object> analyze(ReviewTask task, Path repoDir) throws Exception {
        List<DiffFile> changed = diffParser.parse(task.getDiffText()).stream()
                .filter(f -> !f.binary && (f.addedCount > 0 || f.deletedCount > 0))
                .toList();
        Map<String, String> files = indexRepo(repoDir);

        // 1) 从 diff 提取每个变更文件涉及到的符号
        Map<String, List<String>> fileSymbols = new LinkedHashMap<>();
        List<String> symbolQueue = new ArrayList<>();
        for (DiffFile f : changed) {
            List<String> syms = extractSymbols(f);
            fileSymbols.put(f.path, syms);
            for (String s : syms) {
                if (symbolQueue.size() < MAX_SYMBOLS && !symbolQueue.contains(s)) symbolQueue.add(s);
            }
        }

        // 2) 全仓反向引用查找
        Map<String, Map<String, Integer>> affectedRaw = new HashMap<>();  // 文件 → 符号 → 命中数
        for (String symbol : symbolQueue) {
            for (Map.Entry<String, String> e : files.entrySet()) {
                int hits = countWordOccurrences(e.getValue(), symbol);
                if (hits > 0) {
                    affectedRaw.computeIfAbsent(e.getKey(), k -> new HashMap<>()).merge(symbol, hits, Integer::sum);
                }
            }
        }
        List<Map<String, Object>> affected = affectedRaw.entrySet().stream()
                .map(en -> {
                    List<Map.Entry<String, Integer>> syms = en.getValue().entrySet().stream()
                            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).toList();
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("file", en.getKey());
                    m.put("symbols", syms.stream().map(Map.Entry::getKey).toList());
                    m.put("hits", en.getValue().values().stream().mapToInt(Integer::intValue).sum());
                    m.put("symbolHits", syms.stream()
                            .map(s -> Map.of(s.getKey(), s.getValue())).toList());
                    return m;
                })
                .sorted(Comparator.<Map<String, Object>>comparingInt(m -> (Integer) m.get("hits")).reversed())
                .limit(MAX_AFFECTED)
                .toList();

        // 3) 引用证据（受影响最高的若干文件的匹配行）
        List<String> evidence = new ArrayList<>();
        for (Map<String, Object> a : affected) {
            if (evidence.size() / 3 >= MAX_EVIDENCE_FILES) break;
            String path = (String) a.get("file");
            List<String> symbols = (List<String>) a.get("symbols");
            collectEvidence(files.get(path), path, symbols, evidence);
        }

        // 4) LLM 综合分析
        Map<String, Object> analysis = llmAnalyze(task, changed, fileSymbols, affected, evidence);

        // 5) 组装报告
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("repoUrl", task.getRepoUrl());
        report.put("branch", task.getBranch());
        report.put("changedFiles", changed.size());
        report.put("repoFiles", files.size());
        report.put("symbolsAnalyzed", symbolQueue.size());
        Map<String, Object> stat = new LinkedHashMap<>();
        stat.put("affectedCount", affected.size());
        stat.put("affected", affected);
        stat.put("evidence", evidence);
        report.put("static", stat);
        report.put("analysis", analysis);
        report.put("generatedAt", LocalDateTime.now().toString());
        return report;
    }

    /** 遍历仓库建立「路径 → 文本内容」索引（跳过依赖/构建目录，限量） */
    private Map<String, String> indexRepo(Path dir) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        try (Stream<Path> stream = Files.walk(dir)) {
            for (Path p : stream.filter(Files::isRegularFile).toList()) {
                if (files.size() >= MAX_FILES) break;
                String rel = dir.relativize(p).toString();
                if (rel.contains("/")) {
                    boolean skip = false;
                    for (String seg : rel.split("/")) {
                        if (SKIP_DIRS.contains(seg)) { skip = true; break; }
                    }
                    if (skip) continue;
                } else if (SKIP_DIRS.contains(rel)) {
                    continue;
                }
                String name = rel.contains("/") ? rel.substring(rel.lastIndexOf('/') + 1) : rel;
                int dot = name.lastIndexOf('.');
                if (dot < 0 || !CODE_EXTS.contains(name.substring(dot + 1).toLowerCase())) continue;
                if (Files.size(p) > MAX_FILE_BYTES) continue;
                files.put(rel, Files.readString(p, StandardCharsets.UTF_8));
            }
        }
        return files;
    }

    /** 从 diff 变更行提取符号（标识符加权筛选，另加文件名作为符号） */
    private List<String> extractSymbols(DiffFile f) {
        Map<String, int[]> freq = new LinkedHashMap<>(); // 符号 → [次数, 加权分]
        boolean useNew = f.type != DiffFile.ChangeType.DELETE;
        for (DiffFile.Hunk h : f.hunks) {
            for (DiffFile.DiffLine l : h.lines) {
                boolean target = useNew ? l.type == DiffFile.LineType.ADD : l.type == DiffFile.LineType.DEL;
                if (!target) continue;
                Matcher m = TOKEN.matcher(l.text);
                while (m.find()) {
                    String t = m.group();
                    if (t.length() < 3 || KEYWORDS.contains(t)) continue;
                    int[] v = freq.computeIfAbsent(t, k -> new int[2]);
                    v[0]++;
                    v[1] += scoreOf(t);
                }
            }
        }
        String base = f.path.contains("/") ? f.path.substring(f.path.lastIndexOf('/') + 1) : f.path;
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            String stem = base.substring(0, dot);
            if (stem.length() >= 3 && !KEYWORDS.contains(stem)) {
                int[] v = freq.computeIfAbsent(stem, k -> new int[2]);
                v[0] += 2;
                v[1] += 8;
            }
        }
        return freq.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue()[1] + b.getValue()[0], a.getValue()[1] + a.getValue()[0]))
                .limit(SYMBOLS_PER_FILE)
                .map(Map.Entry::getKey)
                .toList();
    }

    /** 符号显著度加权：驼峰命名 / 大写开头（类名）/ 全大写常量更可能是关键符号 */
    private static int scoreOf(String t) {
        int s = 0;
        boolean hasUpper = false, hasLower = false;
        for (char c : t.toCharArray()) {
            if (Character.isUpperCase(c)) hasUpper = true;
            if (Character.isLowerCase(c)) hasLower = true;
        }
        if (hasUpper && hasLower) s += 5;
        if (Character.isUpperCase(t.charAt(0))) s += 3;
        if (!hasLower && t.length() >= 4) s += 2;
        return s;
    }

    /** 词边界命中计数（避免 user 误匹配 username） */
    private static int countWordOccurrences(String content, String symbol) {
        if (content == null || symbol == null) return 0;
        int idx = 0, hits = 0;
        while ((idx = content.indexOf(symbol, idx)) >= 0) {
            int end = idx + symbol.length();
            boolean leftOk = idx == 0 || !isWordChar(content.charAt(idx - 1));
            boolean rightOk = end >= content.length() || !isWordChar(content.charAt(end));
            if (leftOk && rightOk) hits++;
            idx = end;
        }
        return hits;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    /** 收集受影响文件中命中符号的示例行（每文件最多 3 行） */
    private static void collectEvidence(String content, String path, List<String> symbols, List<String> out) {
        if (content == null) return;
        String[] lines = content.replace("\r\n", "\n").split("\n", -1);
        int found = 0;
        for (int i = 0; i < lines.length && found < 3; i++) {
            for (String s : symbols) {
                if (countWordOccurrences(lines[i], s) > 0) {
                    out.add(path + ":" + (i + 1) + ": " + clip(lines[i].trim(), 200));
                    found++;
                    break;
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // LLM 综合分析
    // ------------------------------------------------------------------

    private Map<String, Object> llmAnalyze(ReviewTask task, List<DiffFile> changed,
                                          Map<String, List<String>> fileSymbols,
                                          List<Map<String, Object>> affected, List<String> evidence) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 仓库信息\n仓库：").append(task.getRepoName());
        if (task.getBranch() != null && !task.getBranch().isBlank()) sb.append("　分支：").append(task.getBranch());
        sb.append("（分析基于克隆的全仓代码与本次提交 diff）\n\n");
        sb.append("## 变更文件（").append(changed.size()).append(" 个）\n");
        for (DiffFile f : changed) {
            sb.append("- ").append(f.path).append("（+").append(f.addedCount).append("/-").append(f.deletedCount).append("）\n");
        }
        sb.append("\n## 变更涉及的符号\n");
        for (Map.Entry<String, List<String>> e : fileSymbols.entrySet()) {
            sb.append("- ").append(e.getKey()).append("：").append(String.join(", ", e.getValue())).append('\n');
        }
        sb.append("\n## 静态引用分析（全仓反向查找的受影响文件，非变更文件本身）\n");
        if (affected.isEmpty()) {
            sb.append("（无静态引用命中：变更符号未被仓库其他文件直接引用）\n");
        } else {
            for (Map<String, Object> a : affected) {
                sb.append("- ").append(a.get("file")).append("（命中 ")
                        .append(a.get("hits")).append(" 次，符号：")
                        .append(String.join(", ", (List<String>) a.get("symbols"))).append("）\n");
            }
        }
        if (!evidence.isEmpty()) {
            sb.append("\n## 引用证据（受影响文件中的匹配行）\n");
            for (String e : evidence) sb.append("- ").append(e).append('\n');
        }

        String prompt = """
                你是资深架构师。请基于以下静态引用分析结果与变更内容，评估本次提交对整个仓库的影响面：
                - 受影响模块：判断哪些文件/模块真正受影响（调用关系、依赖、配置），给出影响原因与风险级别
                - 隐藏风险：diff 本身看不出来的连带风险（如调用方未同步修改、契约变更、并发/事务边界）
                - 回归测试建议：需要重点回归的用例/模块

                ## 输出格式（严格遵守）
                只输出一个 JSON 对象，不得包含 markdown 代码块或其他文字：
                {"summary": "一句话中文结论", "overallRisk": "HIGH|MEDIUM|LOW", "affectedModules": [{"file": "文件路径", "symbols": ["引用的符号"], "risk": "HIGH|MEDIUM|LOW", "reason": "受影响原因"}], "hiddenRisks": [{"risk": "风险描述", "evidence": "证据", "suggestion": "建议"}], "testSuggestions": ["回归测试建议"]}

                """ + clip(sb.toString(), PROMPT_CAP);

        try {
            LlmModels.LlmResponse resp = llm.chat(
                    List.of(LlmModels.LlmMessage.system("你是严谨的架构师，只输出 JSON。"),
                            LlmModels.LlmMessage.user(prompt)),
                    null);
            String json = extractJsonObject(resp.content);
            JsonNode root = om.readTree(json);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("summary", clip(root.path("summary").asText(""), 1000));
            String risk = root.path("overallRisk").asText("");
            out.put("overallRisk", risk.equalsIgnoreCase("HIGH") || risk.equalsIgnoreCase("MEDIUM")
                    || risk.equalsIgnoreCase("LOW") ? risk.toUpperCase() : "UNKNOWN");
            List<Object> mods = new ArrayList<>();
            for (JsonNode n : root.path("affectedModules")) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("file", clip(n.path("file").asText(""), 200));
                List<String> ss = new ArrayList<>();
                n.path("symbols").forEach(s -> ss.add(clip(s.asText(""), 100)));
                m.put("symbols", ss);
                m.put("risk", clip(n.path("risk").asText("MEDIUM"), 20));
                m.put("reason", clip(n.path("reason").asText(""), 1000));
                if (!((String) m.get("file")).isEmpty()) mods.add(m);
            }
            out.put("affectedModules", mods);
            List<Object> risks = new ArrayList<>();
            for (JsonNode n : root.path("hiddenRisks")) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("risk", clip(n.path("risk").asText(""), 1000));
                m.put("evidence", clip(n.path("evidence").asText(""), 1000));
                m.put("suggestion", clip(n.path("suggestion").asText(""), 1500));
                if (!((String) m.get("risk")).isEmpty()) risks.add(m);
            }
            out.put("hiddenRisks", risks);
            List<Object> tests = new ArrayList<>();
            for (JsonNode n : root.path("testSuggestions")) {
                String t = clip(n.asText(""), 500);
                if (!t.isEmpty()) tests.add(t);
            }
            out.put("testSuggestions", tests);
            return out;
        } catch (Exception e) {
            log.warn("影响分析 LLM 输出解析失败，回退为原文: {}", e.getMessage());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("summary", "（模型输出无法解析为影响报告）" + clip(e.getMessage() == null ? "" : e.getMessage(), 300));
            out.put("overallRisk", "UNKNOWN");
            out.put("affectedModules", List.of());
            out.put("hiddenRisks", List.of());
            out.put("testSuggestions", List.of());
            out.put("parseError", true);
            return out;
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static String extractJsonObject(String content) {
        if (content == null) throw new IllegalArgumentException("模型输出为空");
        String s = content.trim();
        if (s.contains("```")) {
            int start = s.indexOf("```");
            int bodyStart = s.indexOf('\n', start);
            int end = s.lastIndexOf("```");
            if (bodyStart > 0 && end > bodyStart) s = s.substring(bodyStart + 1, end);
        }
        int l = s.indexOf('{');
        int r = s.lastIndexOf('}');
        if (l < 0 || r <= l) throw new IllegalArgumentException("输出中未找到 JSON 对象");
        return s.substring(l, r + 1);
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignore) {
                    // 忽略清理失败（临时目录由 OS 兜底）
                }
            });
        } catch (IOException ignore) {
            // 忽略
        }
    }

    /** 日志脱敏：去掉 URL 中可能的用户名/密码 */
    private static String sanitize(String url) {
        if (url == null) return "";
        return url.replaceAll("://[^@/]+@", "://***@");
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    /** 供队列统计等场景查询（保留扩展） */
    public boolean busy() {
        return pool != null && pool.getActiveCount() > 0;
    }
}
