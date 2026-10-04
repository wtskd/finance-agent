package com.jiang.financeagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 大模型客户端：把「发一次 chat/completions 请求」封装成一个方法。
 *
 * 【为什么要单独抽一个类】
 *   后面每一轮工具调用都要发一次请求。如果每次都写一遍 HttpClient、拼 JSON、判状态码，
 *   代码会重复且容易漏掉错误处理。抽出来还有个好处：以后换模型只改这一个文件。
 */
public class LlmClient {

    private static final ObjectMapper M = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static final String URL = "https://api.deepseek.com/chat/completions";
    private static final String MODEL = "deepseek-chat";

    /** 一次模型响应的关键信息：消息体 + token 用量（token 就是钱，必须能看到） */
    public record Reply(ObjectNode message, int promptTokens, int completionTokens) {
        public int totalTokens() {
            return promptTokens + completionTokens;
        }
    }

    private final String apiKey;

    /**
     * 累计 token。
     *
     * 【为什么放在客户端而不是各调用方自己加】
     *   图编排模式下，一次问答会分散触发 4~6 次模型调用（意图/生成/报告…），
     *   每次由不同节点发出。让每个节点各自统计再汇总，等于把成本核算的活
     *   摊给了业务流程。放在客户端，谁调用都自动记账，一处汇总。
     */
    private int totalTokens = 0;

    public int totalTokens() {
        return totalTokens;
    }

    public LlmClient(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("缺少 DEEPSEEK_API_KEY");
        }
        this.apiKey = apiKey;
    }

    /** 供 Agent 复用同一个 ObjectMapper */
    public static ObjectMapper mapper() {
        return M;
    }

    /**
     * 发一次请求。
     *
     * @param messages 对话历史
     * @param tools    工具定义（可为空数组，表示这次不给模型任何工具）
     */
    public Reply chat(ArrayNode messages, ArrayNode tools) throws Exception {
        ObjectNode body = M.createObjectNode();
        body.put("model", MODEL);
        body.set("messages", messages);
        body.set("tools", tools);
        // tool_choice=auto：由模型自己决定是否调用工具，我们不做强制
        body.put("tool_choice", "auto");

        String payload = M.writeValueAsString(body);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                // 超时给 90 秒：模型带工具推理时会比普通对话慢
                .timeout(Duration.ofSeconds(90))
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response =
                HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                    "模型请求失败 " + response.statusCode() + "：" + response.body());
        }

        JsonNode root = M.readTree(response.body());
        ObjectNode message = (ObjectNode) root.path("choices").path(0).path("message");
        JsonNode usage = root.path("usage");

        int prompt = usage.path("prompt_tokens").asInt(0);
        int completion = usage.path("completion_tokens").asInt(0);
        totalTokens += prompt + completion;

        return new Reply(message, prompt, completion);
    }

    /**
     * 单轮生成：给一段系统提示词和一段用户输入，拿回纯文本回答。
     *
     * 【为什么第 9 步需要它】
     *   手写版（第 4 步）用的是 tool-calling 循环：模型自主决定调什么工具。
     *   图编排版（第 9 步）把"调什么"写成了图里的节点和边 ——
     *   每个节点内部大多只需要**一次纯生成**，不再需要工具定义。
     *
     *   这不是多余的重载，而是两种编排范式的分界线：
     *     tool-calling 循环 = 把决策权交给模型
     *     图编排           = 把决策权收回代码，模型只负责节点内的单步任务
     */
    public String complete(String systemPrompt, String userPrompt) throws Exception {
        ArrayNode messages = M.createArrayNode();
        messages.add(textMessage("system", systemPrompt));
        messages.add(textMessage("user", userPrompt));
        return complete(messages);
    }

    /** 单轮生成（可自带完整消息列表，用于需要多段上下文的节点） */
    public String complete(ArrayNode messages) throws Exception {
        Reply reply = chat(messages, M.createArrayNode());
        return reply.message().path("content").asText("");
    }

    private static ObjectNode textMessage(String role, String content) {
        ObjectNode node = M.createObjectNode();
        node.put("role", role);
        node.put("content", content);
        return node;
    }
}
