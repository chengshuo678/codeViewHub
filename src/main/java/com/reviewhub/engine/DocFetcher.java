package com.reviewhub.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reviewhub.domain.ReviewTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 参考文档获取：提交时可携带多个需求文档 / 设计文档 wiki 链接。
 * 评审执行时服务端抓取链接内容（HTML 转纯文本）注入评审 prompt；
 * 抓取失败时保留链接并在 prompt 中标注不可用，可配合手动粘贴的文档内容兜底。
 */
@Service
public class DocFetcher {

    private static final Logger log = LoggerFactory.getLogger(DocFetcher.class);

    private static final int FETCH_TIMEOUT_SECONDS = 20;
    private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;   // 单篇原始内容上限 2MB
    private static final int MAX_DOC_CHARS = 20000;             // 单篇注入 prompt 的字符上限
    private static final int MAX_TOTAL_CHARS = 60000;           // 全部文档合计注入上限

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final ObjectMapper om = new ObjectMapper();

    /** 单篇文档的抓取结果 */
    public record DocInfo(String url, String title, String status, int chars, String error) {
    }

    /**
     * 抓取任务的需求/设计文档并生成注入评审 prompt 的上下文。
     * 同时把抓取结果信息回写到 task.docsInfo（供详情页展示）。
     * 任何异常都不阻断评审流程（文档只是参考上下文）。
     */
    public String fetchAndPersist(ReviewTask task) {
        List<DocInfo> reqInfos = new ArrayList<>();
        List<DocInfo> designInfos = new ArrayList<>();
        StringBuilder reqCtx = new StringBuilder();
        StringBuilder designCtx = new StringBuilder();

        int[] budget = {MAX_TOTAL_CHARS};

        for (String url : parseUrls(task.getRequirementDocs())) {
            appendDoc(reqCtx, reqInfos, url, "需求文档", budget);
        }
        String reqText = nz(task.getRequirementDocText());
        if (!reqText.isBlank()) {
            appendPasted(reqCtx, reqInfos, "需求文档（手动粘贴）", reqText, budget);
        }
        for (String url : parseUrls(task.getDesignDocs())) {
            appendDoc(designCtx, designInfos, url, "设计文档", budget);
        }
        String designText = nz(task.getDesignDocText());
        if (!designText.isBlank()) {
            appendPasted(designCtx, designInfos, "设计文档（手动粘贴）", designText, budget);
        }

        try {
            task.setDocsInfo(om.writeValueAsString(java.util.Map.of(
                    "requirement", reqInfos, "design", designInfos)));
        } catch (Exception e) {
            log.warn("docsInfo 序列化失败: {}", e.getMessage());
        }

        StringBuilder sb = new StringBuilder();
        if (reqCtx.length() > 0) {
            sb.append("## 需求文档（对照参考）\n").append(reqCtx).append('\n');
        }
        if (designCtx.length() > 0) {
            sb.append("## 设计文档（对照参考）\n").append(designCtx).append('\n');
        }
        if (sb.length() > 0) {
            sb.append("请结合以上需求/设计文档评审：检查实现是否遗漏需求、与设计不符、接口/命名与设计不一致等，")
                    .append("发现偏差时作为问题输出（rule 可填最接近的规则名或「需求偏差」）。\n\n");
        }
        return sb.toString();
    }

    /** 解析任务里存储的 JSON 链接数组 */
    public List<String> parseUrls(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<String> out = new ArrayList<>();
            om.readTree(json).forEach(n -> {
                String u = n.asText("").trim();
                if (!u.isEmpty()) out.add(u);
            });
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private void appendDoc(StringBuilder ctx, List<DocInfo> infos, String url, String kind, int[] budget) {
        try {
            HttpResponse<byte[]> resp = http.send(
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(FETCH_TIMEOUT_SECONDS))
                            .header("User-Agent", "CodeReviewHub/1.0 (+doc-fetch)")
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                infos.add(new DocInfo(url, null, "FAIL", 0, "HTTP " + resp.statusCode()));
                ctx.append("- ").append(url).append("（抓取失败：HTTP ").append(resp.statusCode()).append("）\n");
                return;
            }
            String body = new String(resp.body(), charsetOf(resp));
            String title = extractTitle(body);
            String text = htmlToText(body);
            int used = appendClipped(ctx, kind, url, title, text, budget);
            infos.add(new DocInfo(url, title, "OK", used, null));
        } catch (Exception e) {
            String err = clip(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), 200);
            infos.add(new DocInfo(url, null, "FAIL", 0, err));
            ctx.append("- ").append(url).append("（抓取失败：").append(err).append("，请仅依据代码评审）\n");
        }
    }

    private void appendPasted(StringBuilder ctx, List<DocInfo> infos, String title, String text, int[] budget) {
        int used = appendClipped(ctx, "粘贴文档", null, title, text, budget);
        infos.add(new DocInfo(null, title, "OK", used, null));
    }

    private int appendClipped(StringBuilder ctx, String kind, String url, String title, String text, int[] budget) {
        String clipped = text.length() > MAX_DOC_CHARS ? text.substring(0, MAX_DOC_CHARS) + "\n…（内容过长已截断）" : text;
        int allowed = Math.min(clipped.length(), Math.max(0, budget[0]));
        if (allowed <= 0) {
            ctx.append("- ").append(title != null ? title : url).append("（已达注入上限，跳过）\n");
            return 0;
        }
        String content = allowed < clipped.length() ? clipped.substring(0, allowed) + "\n…（已达注入上限）" : clipped;
        budget[0] -= content.length();
        ctx.append("### ").append(title != null && !title.isBlank() ? title : url).append('\n');
        if (url != null) ctx.append("来源：").append(url).append('\n');
        ctx.append(content.trim()).append("\n\n");
        return content.length();
    }

    /** HTML 转纯文本：去脚本样式、标签换行、解码常见实体 */
    static String htmlToText(String html) {
        String s = html == null ? "" : html;
        s = s.replaceAll("(?is)<(script|style|noscript)[^>]*>.*?</\\1>", " ");
        s = s.replaceAll("(?i)<br\\s*/?>", "\n");
        s = s.replaceAll("(?i)</(p|div|li|tr|h[1-6]|section|article|blockquote|pre|table)>", "\n");
        s = s.replaceAll("<[^>]+>", " ");
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&ldquo;", "“").replace("&rdquo;", "”");
        s = s.replaceAll("[ \\t\\x0B\\f]+", " ");
        s = s.replaceAll("\\n\\s*\\n+", "\n");
        return s.trim();
    }

    static String extractTitle(String html) {
        if (html == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?i)<title[^>]*>(.*?)</title>").matcher(html);
        if (m.find()) {
            String t = htmlToText(m.group(1));
            return t.isBlank() ? null : clip(t, 120);
        }
        return null;
    }

    private static java.nio.charset.Charset charsetOf(HttpResponse<?> resp) {
        String ct = resp.headers().firstValue("Content-Type").orElse("");
        for (String p : ct.split(";")) {
            String[] kv = p.trim().split("=", 2);
            if (kv.length == 2 && kv[0].equalsIgnoreCase("charset")) {
                try {
                    return java.nio.charset.Charset.forName(kv[1].trim());
                } catch (Exception ignore) {
                    return java.nio.charset.StandardCharsets.UTF_8;
                }
            }
        }
        return java.nio.charset.StandardCharsets.UTF_8;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String clip(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }
}
