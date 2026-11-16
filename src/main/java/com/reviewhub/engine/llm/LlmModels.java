package com.reviewhub.engine.llm;

import java.util.List;

/** 与 LLM 交互的消息/工具模型（OpenAI Chat Completions 兼容） */
public class LlmModels {

    public static class ToolCall {
        public String id;
        public String name;
        public String arguments;

        public ToolCall() {
        }

        public ToolCall(String id, String name, String arguments) {
            this.id = id;
            this.name = name;
            this.arguments = arguments;
        }
    }

    public static class LlmMessage {
        public String role;             // system / user / assistant / tool
        public String content;
        public String toolCallId;       // role=tool 时必填
        public String name;             // role=tool 时的工具名
        public List<ToolCall> toolCalls; // role=assistant 的工具调用

        public static LlmMessage system(String content) {
            LlmMessage m = new LlmMessage();
            m.role = "system";
            m.content = content;
            return m;
        }

        public static LlmMessage user(String content) {
            LlmMessage m = new LlmMessage();
            m.role = "user";
            m.content = content;
            return m;
        }

        public static LlmMessage assistant(String content, List<ToolCall> toolCalls) {
            LlmMessage m = new LlmMessage();
            m.role = "assistant";
            m.content = content;
            m.toolCalls = toolCalls;
            return m;
        }

        public static LlmMessage tool(String toolCallId, String name, String content) {
            LlmMessage m = new LlmMessage();
            m.role = "tool";
            m.toolCallId = toolCallId;
            m.name = name;
            m.content = content;
            return m;
        }
    }

    public static class LlmResponse {
        public String content;
        public List<ToolCall> toolCalls;

        public boolean hasToolCalls() {
            return toolCalls != null && !toolCalls.isEmpty();
        }
    }

    public static class ToolSpec {
        public final String name;
        public final String description;
        public final String parametersJson;

        public ToolSpec(String name, String description, String parametersJson) {
            this.name = name;
            this.description = description;
            this.parametersJson = parametersJson;
        }
    }

    public static final List<ToolSpec> REVIEW_TOOLS = List.of(
            new ToolSpec("file_read",
                    "读取本次提交中指定源文件的完整内容（评审上下文）。",
                    "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"description\":\"文件路径\"}},\"required\":[\"path\"]}"),
            new ToolSpec("code_search",
                    "在本次提交的代码中搜索关键字，返回匹配的文件与行。",
                    "{\"type\":\"object\",\"properties\":{\"keyword\":{\"type\":\"string\",\"description\":\"搜索关键字\"}},\"required\":[\"keyword\"]}")
    );
}
