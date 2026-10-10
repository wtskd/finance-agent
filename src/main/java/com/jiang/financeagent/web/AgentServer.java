package com.jiang.financeagent.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jiang.financeagent.agent.LlmClient;
import com.jiang.financeagent.graph.FinanceGraph;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * 第 10 步：给 Agent 套一个最小 HTTP 服务，让浏览器能直接问它。
 *
 * ============================================================
 * 一、为什么用 JDK 自带的 HttpServer，而不是 Spring Boot
 * ============================================================
 *   这是刻意的选择，不是偷懒：
 *
 *   1. **零新依赖**。`com.sun.net.httpserver` 从 JDK 6 起就在
 *      `jdk.httpserver` 模块里，不用改 pom、不用拉几十个 jar。
 *   2. **它够用**。这个界面只需要两个端点：
 *        GET  /          → 返回一个 HTML 文件
 *        POST /api/ask   → 收一个问题，返回答案
 *      为两个端点引一整套 Web 框架，是拿架构换不了任何东西。
 *   3. **不掩盖真实逻辑**。用 Spring Boot 的话，面试官会问"你做了什么"，
 *      答案是"我配了几个注解"；用 HttpServer，答案是"我自己写了路由、
 *      自己去读了 body、自己拼了响应"——**后者才证明你理解 HTTP**。
 *
 *   ⚠️ 但要诚实说明它的边界：没有模板引擎、没有参数校验框架、
 *      没有连接池、没有优雅关闭、没有访问日志。**生产不该这么写。**
 *      这里的定位是"演示与自测"，不是"上线"。
 *
 * ============================================================
 * 二、线程安全：每个请求一个独立的 Agent，不共享任何可变状态
 * ============================================================
 *   HttpServer 的线程池里多个请求会并发执行，所以有两个坑：
 *
 *   - 如果共用一个 LlmClient，它内部的 token 累计计数器会被并发写坏，
 *     而且你无法区分"这次的 token"和"别人那次的 token"。
 *   - 如果共用一个 FinanceGraph，更糟 —— 第 9 步已经实测过
 *     框架的 CompiledGraph 状态会跨 invoke 残留。
 *
 *   所以这里**每个请求现建 LlmClient + 现建 FinanceGraph**。
 *   建对象本身没有任何网络开销，代价可以忽略。
 *
 *   顺带一提：这也解释了为什么第 9 步"每次问答重新 compile 图"是对的 ——
 *   它天然兼容并发，不需要为多线程再做一层隔离。
 */
public final class AgentServer {

    private static final ObjectMapper M = new ObjectMapper();

    /** 前端页面。用相对路径，因为 dev.sh 会把工作目录切到项目根 */
    private static final Path INDEX = Path.of("web", "index.html");

    // ============================================================
    // ⚠️ 一个踩过的坑：用 curl 从 Git Bash 测这个接口，中文会乱码
    // ============================================================
    //   Git Bash 里的 curl.exe 是 Windows 程序，命令行参数要先经过
    //   Windows 的 ANSI 代码页（中文系统上是 936/GBK）转换，
    //   于是 "上个月支出多少？" 到服务端时已经被二次编码。
    //   服务端按 UTF-8 一解 → 直接变成 13 个乱七八糟的字符。
    //
    //   症状很有欺骗性：接口返回 200、SQL 语法正确、模型也能答，
    //   只是**答案永远是错的**（因为问题里"上个月"这三个字已经不存在了）。
    //   我为此排查了好几轮，最后靠把"服务端实际收到的问题原文"打出来才定位到。
    //
    //   两种正确的测法：
    //     1) 把问题写成 JSON 的 Unicode 转义序列（反斜杠 + u + 四位十六进制），
    //        这样整个 body 都是纯 ASCII，不经过任何代码页转换，curl 怎么发都不会坏
    //     2) 直接用浏览器打开页面测（浏览器发的就是标准 UTF-8）
    //
    //   这也是本项目第 4 次栽在 Windows 编码上 —— 但它和第 5 条工程教训
    //   （"先测量再下结论"）是同一个道理：**答案不在推测里，在原始输入里**。
    //   所以我特意在响应里保留了 verifyNote 字段，它会回显服务端收到的
    //   问题原文和长度 —— 下次遇到"规则该拦没拦"，一眼就能看出是输入坏了。
    // ============================================================

    private final String apiKey;

    /**
     * 会话记忆：sessionId → 该会话的历史摘要列表。
     *
     * ============================================================
     * 为什么 Web 层要自己存会话，而图不存
     * ============================================================
     *   图每次都是新建的（见 FinanceGraph.build() 的注释），它天然无状态，
     *   所以"记住上一轮"这件事必须由外部承担。CLI 版是 Step9Main 里的一个局部变量，
     *   Web 版就是下面这个 Map —— 职责一样，只是生命周期不同：
     *     · CLI：进程退出就没了
     *     · Web：服务活着就在，多个浏览器各占一个 sessionId
     *
     *   ⚠️ 诚实标注边界：这是**内存会话**，没有 TTL、没有 LRU、没有持久化。
     *      服务重启会话就丢；长期运行会缓慢吃内存（单会话长度有上限，
     *      但会话数量本身没有上限）。生产环境应该换成 Redis + TTL，
     *      这里定位是本地演示。
     *
     * 线程安全：用 ConcurrentHashMap.compute —— 它对同一个 key 是原子的，
     *   并发请求不会把同一个会话的列表写坏。
     *   （每个请求本身仍然各建各的图，见类注释第二节。）
     */
    private final Map<String, List<String>> sessions = new ConcurrentHashMap<>();

    /** 单个会话最多保留多少条历史（约 6 轮），防止 prompt 无限膨胀 */
    private static final int MAX_SESSION_HISTORY = 12;

    public AgentServer(String apiKey) {
        this.apiKey = apiKey;
    }

    /** 取某个会话的历史（复制一份，避免使用期间被并发改掉） */
    private List<String> historyOf(String sessionId) {
        List<String> history = sessions.get(sessionId);
        return history == null ? List.of() : List.copyOf(history);
    }

    /** 把一轮对话追加进会话历史，并裁剪到上限 */
    private void appendHistory(String sessionId, String line) {
        sessions.compute(sessionId, (id, old) -> {
            List<String> next = new ArrayList<>(old == null ? List.of() : old);
            next.add(line);
            return next.size() <= MAX_SESSION_HISTORY
                    ? next
                    : new ArrayList<>(next.subList(next.size() - MAX_SESSION_HISTORY, next.size()));
        });
    }

    public void start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/", this::handleIndex);
        server.createContext("/api/ask", this::handleAsk);

        // 固定 4 个线程：这个 Demo 同时不会有很多人用，
        // 给太多线程只会让并发的模型请求一起变慢，还更容易触发限流。
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();

        System.out.println("服务已启动： http://localhost:" + port);
        System.out.println("页面文件  ： " + INDEX.toAbsolutePath()
                + (Files.exists(INDEX) ? "" : "   ⚠ 文件不存在，页面会 404"));
        System.out.println("按 Ctrl+C 停止");
    }

    // ============================================================
    // 路由
    // ============================================================

    private void handleIndex(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (!path.equals("/") && !path.equals("/index.html")) {
            respond(exchange, 404, "text/plain; charset=utf-8", "Not Found");
            return;
        }
        if (!Files.exists(INDEX)) {
            respond(exchange, 500, "text/plain; charset=utf-8",
                    "找不到前端页面：" + INDEX.toAbsolutePath());
            return;
        }
        respond(exchange, 200, "text/html; charset=utf-8", Files.readString(INDEX, StandardCharsets.UTF_8));
    }

    private void handleAsk(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, "application/json; charset=utf-8",
                    "{\"ok\":false,\"error\":\"请用 POST\"}");
            return;
        }

        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        long start = System.currentTimeMillis();

        // 每个请求独立一份 —— 见类注释第二节
        LlmClient llm = new LlmClient(apiKey);
        // verbose=false：节点日志打到控制台，但网页不需要刷屏
        FinanceGraph graph = new FinanceGraph(llm, false);

        ObjectNode response = M.createObjectNode();
        try {
            JsonNode request = M.readTree(body);
            String question = request.path("question").asText("").trim();
            String sessionId = request.path("sessionId").asText("").trim();

            if (question.isEmpty()) {
                response.put("ok", false);
                response.put("error", "问题不能为空");
            } else {
                // 多轮：把这个会话的历史交给图。图自己不记忆（见 FinanceGraph.ask 的注释）。
                // 不带 sessionId 时等同于单轮 —— 保持向后兼容。
                List<String> history = sessionId.isEmpty() ? List.of() : historyOf(sessionId);

                Map<String, Object> state = graph.ask(question, history);

                if (!sessionId.isEmpty()) {
                    appendHistory(sessionId, FinanceGraph.historyLine(question, state));
                }
                fillSuccess(response, state, llm.totalTokens(), System.currentTimeMillis() - start);
            }
        } catch (Exception e) {
            // 任何异常都必须转成 JSON 返回，不能让浏览器看到一坨堆栈
            response.put("ok", false);
            response.put("error", e.getClass().getSimpleName() + "：" + e.getMessage());
            response.put("elapsedMs", System.currentTimeMillis() - start);
        }

        respond(exchange, 200, "application/json; charset=utf-8", M.writeValueAsString(response));
    }

    /** 把图跑完之后的最终状态，转成前端要的 JSON */
    private void fillSuccess(ObjectNode response, Map<String, Object> state, int tokens, long elapsedMs) {
        response.put("ok", true);
        response.put("answer", FinanceGraph.answerOf(state));
        response.put("tokens", tokens);
        response.put("elapsedMs", elapsedMs);
        response.put("attempts", asInt(state.get(FinanceGraph.K_ATTEMPTS)));
        response.put("sql", asText(state.get(FinanceGraph.K_SQL)));
        response.put("verifyNote", asText(state.get(FinanceGraph.K_VERIFY_NOTE)));

        // 多轮改写：模型把「那收入呢」补全成了什么。
        // 前端单独显示一行，让用户看得见"系统理解成了什么"。
        // 没有改写时是空串，前端不显示。
        response.put("rewritten", asText(state.get(FinanceGraph.K_REWRITTEN)));

        // 走了哪些节点 —— 这是图编排相对手写循环最直观的可视化收益
        ArrayNode trace = response.putArray("trace");
        Object raw = state.get(FinanceGraph.K_TRACE);
        if (raw instanceof List<?> list) {
            list.forEach(item -> trace.add(String.valueOf(item)));
        }

        // SQL 执行结果是 JSON 字符串，解析成对象让前端直接渲染成表格
        String resultJson = asText(state.get(FinanceGraph.K_RESULT));
        if (!resultJson.isBlank()) {
            try {
                JsonNode parsed = M.readTree(resultJson);
                response.set("result", parsed);
            } catch (Exception ignored) {
                response.put("resultText", resultJson);
            }
        }
    }

    // ============================================================
    // 工具方法
    // ============================================================

    private static void respond(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        // 本地演示，关掉缓存，避免改了页面刷新不生效
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String asText(Object value) {
        return value == null ? "" : value.toString();
    }

    private static int asInt(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}
