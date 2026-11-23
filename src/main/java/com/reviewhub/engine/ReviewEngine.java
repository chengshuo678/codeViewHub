package com.reviewhub.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reviewhub.domain.ReviewRule;
import com.reviewhub.domain.ReviewSkill;
import com.reviewhub.domain.ReviewTask;
import com.reviewhub.domain.Severity;
import com.reviewhub.engine.diff.DiffFile;
import com.reviewhub.engine.diff.DiffParser;
import com.reviewhub.engine.llm.LlmClient;
import com.reviewhub.engine.llm.LlmModels;
import com.reviewhub.repo.ReviewRuleRepository;
import com.reviewhub.repo.ReviewSkillRepository;
import com.reviewhub.service.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 评审引擎：编排「确定性流程 + LLM 智能体」（借鉴 open-code-review 混合架构）。
 *
 * 确定性部分：diff 解析、规则路由、文件分组、行号定位、反思上下文提取。
 * 智能体部分：LLM 通过 file_read / code_search 工具主动补齐上下文后输出结构化发现。
 */
@Service
public class ReviewEngine {

    private static final Logger log = LoggerFactory.getLogger(ReviewEngine.class);

    /** 模型输出的单条原始发现 */
    public record RawFinding(String file, int line, Severity severity, String rule, String message, String suggestion) {
    }

    /** 引擎整体输出 */
    public record EngineResult(Integer score, String summary, List<RawFinding> findings) {
    }

    private record GroupResult(int score, String summary, List<RawFinding> findings) {
    }

    private final DiffParser diffParser;
    private final RuleMatcher ruleMatcher;
    private final LlmClient llm;
    private final SettingsService settings;
    private final ReviewRuleRepository ruleRepo;
    private final ReviewSkillRepository skillRepo;
    private final ObjectMapper om = new ObjectMapper();

    public ReviewEngine(DiffParser diffParser, RuleMatcher ruleMatcher, LlmClient llm,
                        SettingsService settings, ReviewRuleRepository ruleRepo, ReviewSkillRepository skillRepo) {
        this.diffParser = diffParser;
        this.ruleMatcher = ruleMatcher;
        this.llm = llm;
        this.settings = settings;
        this.ruleRepo = ruleRepo;
        this.skillRepo = skillRepo;
    }

    /** 执行一次完整评审（同步，由队列 worker 调用）。docsContext 为需求/设计文档上下文（可为空） */
    public EngineResult review(ReviewTask task, String docsContext) {
        Map<String, String> bundle = parseBundle(task.getFilesBundle());
        List<DiffFile> all = diffParser.parse(task.getDiffText());
        List<ReviewRule> enabledRules = ruleRepo.findByEnabledTrueOrderById();

        // 确定性文件筛选：跳过二进制、纯删除、无新增行的文件，且至少命中一条规则
        List<DiffFile> reviewable = new ArrayList<>();
        for (DiffFile f : all) {
            if (f.binary || f.type == DiffFile.ChangeType.DELETE || f.addedCount == 0) continue;
            if (ruleMatcher.match(enabledRules, f).isEmpty()) continue;
            reviewable.add(f);
        }
        if (reviewable.isEmpty()) {
            String why = all.isEmpty() ? "提交内容为空" : "没有文件命中已启用的评审规则";
            return new EngineResult(null, why, List.of());
        }

        ReviewSkill skill = pickSkill();

        List<RawFinding> findings = new ArrayList<>();
        List<String> summaries = new ArrayList<>();
        List<Integer> scores = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        for (List<DiffFile> group : groupFiles(reviewable)) {
            try {
                GroupResult gr = reviewGroup(task, group, enabledRules, skill, bundle, docsContext);
                for (RawFinding rf : gr.findings()) {
                    findings.add(new RawFinding(rf.file(), clampLine(rf.file(), rf.line(), all),
                            rf.severity(), rf.rule(), rf.message(), rf.suggestion()));
                }
                scores.add(gr.score());
                if (gr.summary() != null && !gr.summary().isBlank()) summaries.add(gr.summary());
            } catch (Exception e) {
                log.warn("分组评审失败: {}", e.getMessage(), e);
                errors.add(e.getMessage());
            }
        }

        if (scores.isEmpty()) {
            throw new IllegalStateException("全部文件分组评审失败：" + String.join("；", errors));
        }

        // 反思过滤（可配置关闭）
        if (settings.getBool("review.reflection", true) && !findings.isEmpty()) {
            findings = reflect(task, findings, all);
        }

        int score = (int) Math.round(scores.stream().mapToInt(Integer::intValue).average().orElse(75));
        StringBuilder summary = new StringBuilder(String.join("；", summaries));
        if (!errors.isEmpty()) {
            summary.append("（").append(errors.size()).append(" 个分组评审失败）");
        }
        return new EngineResult(score, clip(summary.toString(), 900), findings);
    }

    // ------------------------------------------------------------------
    // 单组评审：Agent 循环
    // ------------------------------------------------------------------

    private GroupResult reviewGroup(ReviewTask task, List<DiffFile> group,
                                    List<ReviewRule> enabledRules, ReviewSkill skill,
                                    Map<String, String> bundle, String docsContext) {
        Set<ReviewRule> matched = new LinkedHashSet<>();
        for (DiffFile f : group) matched.addAll(ruleMatcher.match(enabledRules, f));

        List<LlmModels.LlmMessage> messages = new ArrayList<>();
        messages.add(LlmModels.LlmMessage.system(buildSystemPrompt(skill, matched)));
        messages.add(LlmModels.LlmMessage.user(buildUserPrompt(task, group, bundle, docsContext)));

        int maxRounds = settings.getInt("review.maxRounds", 6);
        String lastContent = null;
        for (int round = 1; round <= maxRounds; round++) {
            LlmModels.LlmResponse resp = llm.chat(messages, LlmModels.REVIEW_TOOLS);
            if (resp.hasToolCalls()) {
                messages.add(LlmModels.LlmMessage.assistant(resp.content, resp.toolCalls));
                for (LlmModels.ToolCall tc : resp.toolCalls) {
                    messages.add(LlmModels.LlmMessage.tool(tc.id, tc.name, executeTool(tc, bundle)));
                }
                continue;
            }
            lastContent = resp.content;
            return parseResult(lastContent);
        }
        // 达到轮次上限仍未给出结论
        if (lastContent != null) {
            try {
                return parseResult(lastContent);
            } catch (Exception ignore) {
                // fall through
            }
        }
        throw new IllegalStateException("Agent 超过 " + maxRounds + " 轮仍未输出评审结论");
    }

    private String executeTool(LlmModels.ToolCall call, Map<String, String> bundle) {
        try {
            JsonNode args = om.readTree(call.arguments == null || call.arguments.isBlank() ? "{}" : call.arguments);
            switch (call.name) {
                case "file_read" -> {
                    String path = args.path("path").asText("");
                    String content = resolveFile(bundle, path);
                    if (content == null) {
                        return "文件不可用：" + path + "（提交方未提供该文件内容，请仅依据 diff 评审）";
                    }
                    return clip(content, 12000);
                }
                case "code_search" -> {
                    String keyword = args.path("keyword").asText("");
                    StringBuilder sb = new StringBuilder();
                    int hits = 0;
                    for (Map.Entry<String, String> e : bundle.entrySet()) {
                        String[] ls = e.getValue().replace("\r\n", "\n").split("\n", -1);
                        for (int i = 0; i < ls.length && hits < 30; i++) {
                            if (ls[i].contains(keyword)) {
                                sb.append(e.getKey()).append(':').append(i + 1).append(": ")
                                        .append(clip(ls[i].trim(), 200)).append('\n');
                                hits++;
                            }
                        }
                    }
                    return hits == 0 ? "未搜索到匹配：" + keyword : "共 " + hits + " 处匹配：\n" + sb;
                }
                default -> {
                    return "未知工具：" + call.name;
                }
            }
        } catch (Exception e) {
            return "工具执行失败：" + e.getMessage();
        }
    }

    private String resolveFile(Map<String, String> bundle, String path) {
        if (bundle.isEmpty()) return null;
        if (bundle.containsKey(path)) return bundle.get(path);
        for (Map.Entry<String, String> e : bundle.entrySet()) {
            if (e.getKey().endsWith(path) || e.getKey().endsWith("/" + path)) return e.getValue();
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Prompt 构建
    // ------------------------------------------------------------------

    private String buildSystemPrompt(ReviewSkill skill, Set<ReviewRule> rules) {
        StringBuilder sb = new StringBuilder();
        if (skill != null && skill.getSystemPrompt() != null) {
            sb.append(skill.getSystemPrompt().trim()).append("\n\n");
        }
        sb.append("## 评审规则（只报告符合下列规则范畴的问题，rule 字段填写命中的规则名）\n");
        int i = 1;
        for (ReviewRule r : rules) {
            sb.append(i++).append(". ").append(r.getName()).append('[').append(r.getSeverity()).append(']');
            String tip = r.getPrompt() != null && !r.getPrompt().isBlank() ? r.getPrompt() : r.getDescription();
            if (tip != null && !tip.isBlank()) sb.append("：").append(tip);
            sb.append('\n');
        }
        sb.append("""

                ## 工具使用
                你可以调用工具补齐上下文：
                - file_read(path)：读取本次提交中指定源文件的完整内容
                - code_search(keyword)：在本次提交的代码中搜索关键字
                当 diff 不足以做出判断时，先调用工具再下结论，不要凭猜测报告问题。

                ## 输出格式（严格遵守）
                最终回复必须只是一个 JSON 对象，不得包含 markdown 代码块或其他文字：
                {"score": 0到100的整数质量分, "summary": "一句话中文总结", "findings": [{"file": "文件路径(与diff一致)", "line": 新增行行号, "severity": "BLOCKER|CRITICAL|MAJOR|MINOR|INFO", "rule": "命中的规则名", "message": "问题描述", "suggestion": "修改建议"}]}
                约束：
                - line 必须是该文件 diff 中以 + 开头的新增行行号
                - 没有发现问题时 findings 为 []
                - 每条发现必须有 diff 或工具结果中的明确依据""");
        return sb.toString();
    }

    private String buildUserPrompt(ReviewTask task, List<DiffFile> group, Map<String, String> bundle,
                                   String docsContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 仓库信息\n仓库：").append(nz(task.getRepoName()));
        if (notBlank(task.getBranch())) sb.append("　分支：").append(task.getBranch());
        if (notBlank(task.getAuthor())) sb.append("　作者：").append(task.getAuthor());
        sb.append("\n\n");
        if (notBlank(docsContext)) {
            sb.append(docsContext).append('\n');
        }
        sb.append("## file_read 可读取的文件\n");
        if (bundle.isEmpty()) {
            sb.append("（提交方未提供文件内容包，请仅依据 diff 评审）\n");
        } else {
            for (String p : bundle.keySet()) sb.append("- ").append(clip(p, 200)).append('\n');
        }
        sb.append("\n## 代码变更\n```diff\n");
        for (DiffFile f : group) sb.append(renderDiff(f));
        sb.append("```\n请开始评审。");
        return sb.toString();
    }

    /** 将解析结果重新渲染为标准 diff 文本（仅本组文件） */
    private String renderDiff(DiffFile f) {
        StringBuilder sb = new StringBuilder();
        sb.append("diff --git a/").append(f.path).append(" b/").append(f.path).append('\n');
        if (f.type == DiffFile.ChangeType.ADD) sb.append("new file mode 100644\n");
        sb.append("--- a/").append(f.oldPath).append('\n');
        sb.append("+++ b/").append(f.path).append('\n');
        for (DiffFile.Hunk h : f.hunks) {
            int oldCount = 0, newCount = 0;
            for (DiffFile.DiffLine l : h.lines) {
                if (l.type == DiffFile.LineType.CONTEXT) { oldCount++; newCount++; }
                else if (l.type == DiffFile.LineType.DEL) oldCount++;
                else newCount++;
            }
            sb.append("@@ -").append(h.oldStart).append(',').append(oldCount)
                    .append(" +").append(h.newStart).append(',').append(newCount).append(" @@\n");
            for (DiffFile.DiffLine l : h.lines) {
                sb.append(switch (l.type) {
                    case ADD -> '+';
                    case DEL -> '-';
                    default -> ' ';
                }).append(l.text).append('\n');
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 反思过滤
    // ------------------------------------------------------------------

    private List<RawFinding> reflect(ReviewTask task, List<RawFinding> findings, List<DiffFile> files) {
        try {
            StringBuilder ctx = new StringBuilder();
            for (int i = 0; i < findings.size(); i++) {
                RawFinding rf = findings.get(i);
                DiffFile df = findFile(files, rf.file());
                ctx.append("### 发现 ").append(i + 1).append("：").append(rf.file()).append(':').append(rf.line()).append('\n');
                if (df != null) {
                    ctx.append(df.contextAround(rf.line(), 5));
                } else {
                    ctx.append("（未在 diff 中找到该文件）\n");
                }
                ctx.append('\n');
            }

            ArrayNodeWrap arr = new ArrayNodeWrap(findings);
            String prompt = "[REFLECT] 你是评审结果审核员。请核对以下评审发现与对应代码上下文，过滤掉：\n" +
                    "1. 上下文中看不到依据的臆测问题\n" +
                    "2. 重复或明显等价的问题（保留更准确的一条）\n" +
                    "3. 与标注行号明显不符的问题\n" +
                    "其余原样保留（字段保持不变）。只输出保留项组成的 JSON 数组，无保留项输出 []。\n\n" +
                    "## 评审发现\n" + arr.toJson() + "\n\n## 代码上下文\n" + ctx;

            List<LlmModels.LlmMessage> messages = List.of(
                    LlmModels.LlmMessage.system("你是严谨的代码评审结果审核员，只输出 JSON。"),
                    LlmModels.LlmMessage.user(clip(prompt, 40000)));
            LlmModels.LlmResponse resp = llm.chat(messages, null);
            String json = extractJsonArray(resp.content);
            JsonNode root = om.readTree(json);
            if (!root.isArray()) return findings;
            List<RawFinding> kept = new ArrayList<>();
            for (JsonNode n : root) {
                String file = n.path("file").asText("").trim();
                if (file.isEmpty()) continue;
                kept.add(new RawFinding(file, Math.max(1, n.path("line").asInt(1)),
                        Severity.parse(n.path("severity").asText(null), Severity.MAJOR),
                        n.path("rule").asText("通用"),
                        clip(n.path("message").asText(""), 1900),
                        clip(n.path("suggestion").asText(""), 4000)));
            }
            log.info("反思过滤：{} 条 → {} 条", findings.size(), kept.size());
            return kept;
        } catch (Exception e) {
            log.warn("反思过滤失败，保留原始发现: {}", e.getMessage());
            return findings;
        }
    }

    /** 小工具：把发现列表序列化为 JSON */
    private final class ArrayNodeWrap {
        private final List<RawFinding> list;

        ArrayNodeWrap(List<RawFinding> list) {
            this.list = list;
        }

        String toJson() throws Exception {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                RawFinding f = list.get(i);
                if (i > 0) sb.append(',');
                sb.append("{\"file\":").append(om.writeValueAsString(f.file()))
                        .append(",\"line\":").append(f.line())
                        .append(",\"severity\":\"").append(f.severity()).append('"')
                        .append(",\"rule\":").append(om.writeValueAsString(f.rule()))
                        .append(",\"message\":").append(om.writeValueAsString(f.message()))
                        .append(",\"suggestion\":").append(om.writeValueAsString(f.suggestion()))
                        .append('}');
            }
            return sb.append(']').toString();
        }
    }

    // ------------------------------------------------------------------
    // 解析与定位
    // ------------------------------------------------------------------

    private GroupResult parseResult(String content) {
        try {
            String json = extractJsonObject(content);
            JsonNode root = om.readTree(json);
            int score = root.path("score").asInt(-1);
            if (score < 0) score = 75;
            List<RawFinding> fs = new ArrayList<>();
            for (JsonNode n : root.path("findings")) {
                String file = n.path("file").asText("").trim();
                if (file.isEmpty()) continue;
                fs.add(new RawFinding(file, Math.max(1, n.path("line").asInt(1)),
                        Severity.parse(n.path("severity").asText(null), Severity.MAJOR),
                        n.path("rule").asText("通用"),
                        clip(n.path("message").asText("无描述"), 1900),
                        clip(n.path("suggestion").asText(""), 4000)));
            }
            return new GroupResult(score, clip(root.path("summary").asText(""), 400), fs);
        } catch (Exception e) {
            throw new IllegalStateException("无法解析模型输出：" + clip(content, 200), e);
        }
    }

    /** 行号定位：把模型给出的行号吸附到该文件真实的新增行上（借鉴 OCR comment positioning） */
    private int clampLine(String file, int line, List<DiffFile> files) {
        DiffFile df = findFile(files, file);
        if (df == null) return Math.max(1, line);
        List<Integer> added = df.addedLineNumbers();
        if (added.isEmpty()) return 1;
        if (added.contains(line)) return line;
        int best = added.get(0);
        for (int n : added) {
            if (Math.abs(n - line) < Math.abs(best - line)) best = n;
        }
        return best;
    }

    private DiffFile findFile(List<DiffFile> files, String file) {
        for (DiffFile f : files) {
            if (f.path.equals(file)) return f;
        }
        for (DiffFile f : files) {
            if (f.path.endsWith(file) || f.path.endsWith("/" + file)) return f;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 文件分组
    // ------------------------------------------------------------------

    private List<List<DiffFile>> groupFiles(List<DiffFile> files) {
        int maxLines = settings.getInt("review.groupMaxLines", 600);
        List<List<DiffFile>> groups = new ArrayList<>();
        List<DiffFile> cur = new ArrayList<>();
        int lines = 0;
        for (DiffFile f : files) {
            int cost = f.addedCount + f.deletedCount;
            if (!cur.isEmpty() && (lines + cost > maxLines || cur.size() >= 15)) {
                groups.add(cur);
                cur = new ArrayList<>();
                lines = 0;
            }
            cur.add(f);
            lines += cost;
        }
        if (!cur.isEmpty()) groups.add(cur);
        return groups;
    }

    private ReviewSkill pickSkill() {
        Optional<ReviewSkill> s = skillRepo.findByDefaultedTrueAndEnabledTrue();
        if (s.isPresent()) return s.get();
        return skillRepo.findFirstByEnabledTrueOrderById().orElse(null);
    }

    private Map<String, String> parseBundle(String json) {
        if (json == null || json.isBlank()) return Collections.emptyMap();
        try {
            Map<String, String> m = new LinkedHashMap<>();
            JsonNode root = om.readTree(json);
            root.fields().forEachRemaining(e -> m.put(e.getKey(), e.getValue().asText()));
            return m;
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------

    private static String extractJsonObject(String content) {
        if (content == null) throw new IllegalArgumentException("模型输出为空");
        String s = stripFences(content).trim();
        int l = s.indexOf('{');
        int r = s.lastIndexOf('}');
        if (l < 0 || r <= l) throw new IllegalArgumentException("输出中未找到 JSON 对象");
        return s.substring(l, r + 1);
    }

    private static String extractJsonArray(String content) {
        if (content == null) return "[]";
        String s = stripFences(content).trim();
        int l = s.indexOf('[');
        int r = s.lastIndexOf(']');
        if (l < 0 || r <= l) return "[]";
        return s.substring(l, r + 1);
    }

    private static String stripFences(String s) {
        if (s.contains("```")) {
            int start = s.indexOf("```");
            int bodyStart = s.indexOf('\n', start);
            int end = s.lastIndexOf("```");
            if (bodyStart > 0 && end > bodyStart) {
                return s.substring(bodyStart + 1, end);
            }
        }
        return s;
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…(截断)" : s;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
