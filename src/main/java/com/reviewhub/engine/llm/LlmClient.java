package com.reviewhub.engine.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reviewhub.engine.diff.DiffFile;
import com.reviewhub.engine.diff.DiffParser;
import com.reviewhub.service.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * LLM 客户端：
 *  - openai：任意 OpenAI Chat Completions 兼容端点（vLLM / Ollama / OneAPI / 私有网关等）
 *  - mock：内置演示提供者，无需模型即可体验完整评审流程
 */
@Component
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    private final SettingsService settings;
    private final DiffParser diffParser;
    /** 访问大模型的单次请求超时（分钟），启动时通过 review.llm.timeout-minutes 配置，默认 60 */
    private final long timeoutMinutes;
    private final ObjectMapper om = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    public LlmClient(SettingsService settings, DiffParser diffParser,
                     @Value("${review.llm.timeout-minutes:60}") long timeoutMinutes) {
        this.settings = settings;
        this.diffParser = diffParser;
        this.timeoutMinutes = timeoutMinutes;
    }

    public boolean isMock() {
        return "mock".equalsIgnoreCase(settings.get("llm.provider", "mock").trim());
    }

    /** 执行一次对话（含工具定义）。失败时抛出 LlmException */
    public LlmModels.LlmResponse chat(List<LlmModels.LlmMessage> messages, List<LlmModels.ToolSpec> tools) {
        if (isMock()) {
            return mockChat(messages);
        }
        return openaiChat(messages, tools);
    }

    // ------------------------------------------------------------------
    // OpenAI 兼容实现
    // ------------------------------------------------------------------

    private LlmModels.LlmResponse openaiChat(List<LlmModels.LlmMessage> messages, List<LlmModels.ToolSpec> tools) {
        String baseUrl = settings.get("llm.baseUrl").trim();
        String apiKey = settings.get("llm.apiKey").trim();
        String model = settings.get("llm.model").trim();
        if (baseUrl.isEmpty() || model.isEmpty()) {
            throw new LlmException("未配置大模型：请在「系统设置」中填写 baseUrl 与 model，或使用 mock 提供者");
        }

        ObjectNode body = om.createObjectNode();
        body.put("model", model);
        body.put("temperature", parseTemp());
        ArrayNode msgs = body.putArray("messages");
        for (LlmModels.LlmMessage m : messages) {
            ObjectNode n = msgs.addObject();
            n.put("role", m.role);
            if (m.content != null) n.put("content", m.content);
            if (m.toolCallId != null) n.put("tool_call_id", m.toolCallId);
            if (m.name != null) n.put("name", m.name);
            if (m.toolCalls != null && !m.toolCalls.isEmpty()) {
                ArrayNode tcs = n.putArray("tool_calls");
                for (LlmModels.ToolCall tc : m.toolCalls) {
                    ObjectNode t = tcs.addObject();
                    t.put("id", tc.id);
                    t.put("type", "function");
                    ObjectNode fn = t.putObject("function");
                    fn.put("name", tc.name);
                    fn.put("arguments", tc.arguments == null ? "{}" : tc.arguments);
                }
            }
        }
        if (tools != null && !tools.isEmpty()) {
            ArrayNode ts = body.putArray("tools");
            for (LlmModels.ToolSpec t : tools) {
                ObjectNode n = ts.addObject();
                n.put("type", "function");
                ObjectNode fn = n.putObject("function");
                fn.put("name", t.name);
                fn.put("description", t.description);
                fn.set("parameters", readTree(t.parametersJson));
            }
        }

        String url = baseUrl.replaceAll("/+$", "") + "/chat/completions";
        try {
            return sendWithRetry(url, body, apiKey);
        } catch (LlmException e) {
            // 部分私有模型不支持 tools 参数：自动降级为纯对话模式重试一次
            if (tools != null && !tools.isEmpty() && e.getMessage() != null
                    && e.getMessage().toLowerCase().contains("tool")) {
                log.warn("模型似乎不支持工具调用，已降级为纯对话模式: {}", e.getMessage());
                body.remove("tools");
                return sendWithRetry(url, body, apiKey);
            }
            throw e;
        }
    }

    private LlmModels.LlmResponse sendWithRetry(String url, ObjectNode body, String apiKey) {
        RuntimeException last = null;
        String bodyJson;
        try {
            bodyJson = om.writeValueAsString(body);
        } catch (Exception e) {
            throw new LlmException("序列化 LLM 请求失败: " + e.getMessage(), e);
        }
        // 请求详情：INFO 打印摘要，DEBUG 打印完整请求体（含 diff/文件内容，体积大默认不打印）
        log.info("LLM 请求: POST {} | model={} | 消息 {} 条 | 工具 {} 个 | 请求体 {} 字符{}",
                url, body.path("model").asText("?"), body.path("messages").size(),
                body.path("tools").isMissingNode() ? 0 : body.path("tools").size(),
                bodyJson.length(), apiKey.isEmpty() ? "" : " | Bearer 鉴权");
        if (log.isDebugEnabled()) {
            log.debug("LLM 请求体全文: {}", bodyJson);
        }
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                HttpRequest.Builder rb = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofMinutes(timeoutMinutes))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(bodyJson));
                if (!apiKey.isEmpty()) rb.header("Authorization", "Bearer " + apiKey);

                long t0 = System.currentTimeMillis();
                HttpResponse<String> resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
                // 响应结果：状态码 + 耗时 + 内容（成功/失败均打印，超长截断保留前 4000 字符）
                log.info("LLM 响应: HTTP {} | 耗时 {}ms | 响应体 {} 字符 | 内容: {}",
                        resp.statusCode(), System.currentTimeMillis() - t0,
                        resp.body() == null ? 0 : resp.body().length(),
                        truncate(resp.body(), 4000));
                if (resp.statusCode() >= 500 || resp.statusCode() == 429) {
                    throw new java.io.IOException("HTTP " + resp.statusCode() + ": " + snippet(resp.body()));
                }
                if (resp.statusCode() >= 400) {
                    throw new LlmException("模型服务返回 " + resp.statusCode() + ": " + snippet(resp.body()));
                }
                return parseResponse(resp.body());
            } catch (LlmException e) {
                throw e;
            } catch (Exception e) {
                // 连接拒绝等异常 getMessage() 可能为 null，兜底打印异常类名便于定位
                String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                last = new LlmException("调用大模型失败（第 " + attempt + " 次）: " + reason, e);
                log.warn("LLM 调用失败(第 {} 次): {} | url={}", attempt, reason, url);
                sleep(attempt * 2000L);
            }
        }
        throw last;
    }

    private LlmModels.LlmResponse parseResponse(String json) {
        try {
            JsonNode root = om.readTree(json);
            JsonNode choice = root.path("choices").path(0);
            JsonNode msg = choice.path("message");
            LlmModels.LlmResponse r = new LlmModels.LlmResponse();
            r.content = msg.hasNonNull("content") ? msg.get("content").asText() : null;
            List<LlmModels.ToolCall> calls = new ArrayList<>();
            if (msg.has("tool_calls") && msg.get("tool_calls").isArray()) {
                for (JsonNode tc : msg.get("tool_calls")) {
                    calls.add(new LlmModels.ToolCall(
                            tc.path("id").asText("call_1"),
                            tc.path("function").path("name").asText(""),
                            tc.path("function").path("arguments").asText("{}")));
                }
            }
            r.toolCalls = calls;
            if ((r.content == null || r.content.isBlank()) && calls.isEmpty()) {
                throw new LlmException("模型返回了空内容（finish_reason=" + choice.path("finish_reason").asText("?") + "）");
            }
            return r;
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException("解析模型响应失败: " + e.getMessage(), e);
        }
    }

    private double parseTemp() {
        try {
            return Double.parseDouble(settings.get("llm.temperature", "0.1").trim());
        } catch (Exception e) {
            return 0.1;
        }
    }

    private JsonNode readTree(String json) {
        try {
            return om.readTree(json);
        } catch (Exception e) {
            return om.createObjectNode();
        }
    }

    // ------------------------------------------------------------------
    // Mock 实现：演示/测试用，无需真实模型
    // ------------------------------------------------------------------

    private LlmModels.LlmResponse mockChat(List<LlmModels.LlmMessage> messages) {
        LlmModels.LlmMessage last = messages.get(messages.size() - 1);

        // 连通性测试
        if ("user".equals(last.role) && last.content != null && last.content.contains("请只回复")) {
            LlmModels.LlmResponse r = new LlmModels.LlmResponse();
            r.content = "pong（Mock 模型在线）";
            return r;
        }

        // 反思阶段：原样返回待核对的发现列表
        if ("user".equals(last.role) && last.content != null && last.content.startsWith("[REFLECT]")) {
            LlmModels.LlmResponse r = new LlmModels.LlmResponse();
            r.content = extractJsonArray(last.content);
            return r;
        }

        // 工具结果已返回 → 输出最终评审 JSON
        if ("tool".equals(last.role)) {
            LlmModels.LlmResponse r = new LlmModels.LlmResponse();
            r.content = mockFinalJson(messages);
            return r;
        }

        // 第一轮：先发起一次工具调用，演示 Agent 循环
        List<DiffFile> files = diffParser.parse(findDiffText(messages));
        if (!files.isEmpty()) {
            LlmModels.LlmResponse r = new LlmModels.LlmResponse();
            r.toolCalls = List.of(new LlmModels.ToolCall("call_mock_1", "file_read",
                    "{\"path\":\"" + files.get(0).path.replace("\"", "\\\"") + "\"}"));
            return r;
        }

        LlmModels.LlmResponse r = new LlmModels.LlmResponse();
        r.content = mockFinalJson(messages);
        return r;
    }

    private String mockFinalJson(List<LlmModels.LlmMessage> messages) {
        List<DiffFile> files = diffParser.parse(findDiffText(messages));
        ArrayNode findings = om.createArrayNode();
        int produced = 0;
        outer:
        for (DiffFile f : files) {
            for (Integer line : f.addedLineNumbers()) {
                if (produced >= 2) break outer;
                ObjectNode n = findings.addObject();
                n.put("file", f.path);
                n.put("line", line);
                n.put("severity", produced == 0 ? "MAJOR" : "MINOR");
                n.put("rule", "代码可读性");
                n.put("message", "[Mock 演示] 这是一条由内置 Mock 模型生成的示例发现，用于演示完整评审流程。");
                n.put("suggestion", "接入真实大模型后，此处将展示 AI 给出的具体修改建议。可在「系统设置」中配置模型连接。");
                produced++;
            }
        }
        ObjectNode out = om.createObjectNode();
        out.put("score", 78);
        out.put("summary", "Mock 模型演示评审：流程与数据链路完整，请在「系统设置」接入真实大模型以获得实际评审能力。");
        out.set("findings", findings);
        return out.toString();
    }

    private String findDiffText(List<LlmModels.LlmMessage> messages) {
        for (LlmModels.LlmMessage m : messages) {
            if ("user".equals(m.role) && m.content != null && m.content.contains("diff --git")) {
                return m.content;
            }
        }
        return "";
    }

    private String extractJsonArray(String content) {
        if (content == null) return "[]";
        // 反思请求中优先取「## 评审发现」标记之后的数组，避免误取 "[REFLECT]" 前缀
        int from = content.indexOf("## 评审发现");
        int s = from >= 0 ? content.indexOf('[', from) : content.indexOf('[');
        int e = content.lastIndexOf(']');
        if (s >= 0 && e > s) return content.substring(s, e + 1);
        return "[]";
    }

    // ------------------------------------------------------------------

    private static String snippet(String s) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ");
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }

    /** 日志用截断：保留原始换行，超长时截断并注明总长度 */
    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "\n…(日志截断，响应体共 " + s.length() + " 字符)";
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /** LLM 调用异常 */
    public static class LlmException extends RuntimeException {
        public LlmException(String message) {
            super(message);
        }

        public LlmException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
