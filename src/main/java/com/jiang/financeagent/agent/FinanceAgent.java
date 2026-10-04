package com.jiang.financeagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jiang.financeagent.tool.SchemaTool;
import com.jiang.financeagent.tool.SqlTool;
import com.jiang.financeagent.util.Text;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 财务问数 Agent 的主循环。
 *
 * 【Agent 到底是什么】
 *   Agent = 模型 + 工具 + 一个「循环」。
 *   循环做的事：发请求 → 模型说要调工具 → 我执行 → 结果塞回历史 → 再发请求
 *   → 直到模型不再要求调工具，给出最终答案。
 *   所谓「自主」，指调几次工具、调哪个、传什么参数由模型自己决定，而不是写死在代码里。
 */
public class FinanceAgent {

    private static final ObjectMapper M = LlmClient.mapper();

    /**
     * 最多循环几轮。
     * 【为什么必须有上限】模型可能陷入「反复查同一件事」的死循环，
     * 每次循环都是一次付费请求。上限既是稳定性保护，也是成本保护。
     */
    private static final int MAX_STEPS = 6;

    /**
     * 同一个错误连续出现几次就放弃。
     *
     * 【为什么光有 MAX_STEPS 不够】
     *   MAX_STEPS 只能防「轮数太多」，防不住「在同一个坑里反复试」。
     *   模型可能换着写法连撞好几次同一个错误，全是无效的付费请求。
     *   所以再加一道：同一错误连续出现 2 次 → 直接终止并如实告知用户。
     */
    private static final int MAX_SAME_ERROR = 2;

    /**
     * 系统提示词（System Prompt）。
     *
     * 【为什么这几条规则是必要的，而不是"锦上添花"】
     *   第 1 条：★★ 注入当前日期。这是实测踩出来的坑 ——
     *           模型不知道今天几号，遇到"上个月"就自己编。实测出现过
     *           「SQL 查出来的是 2026-09 的数据，回答里却写'2024年12月'」：
     *           数字对、标签错，演示时非常尴尬。
     *           根因是系统提示词里从来没告诉它今天是哪天。
     *   第 2 条：Schema 召回不可能 100% 准，必须给模型一条退路。
     *           不给退路，一旦漏召回它就只能编 —— 有了 getFullSchema，最坏情况能自恢复。
     *   第 4 条：我们库里 status 存的是中文「收入/支出」。不明确告诉模型，
     *           它会写 WHERE status = 1，SQL 不报错但永远查不到数据。
     *   第 5 条：金额全是正数，方向由 status 表示。这是本项目最容易出错的地方，
     *           不写清楚，模型算出来的"净收入"一定是错的。
     *   第 6 条：报错要按 errorType/hint 重试，但最多 2 次 —— 这是 Agent 自愈能力的前提。
     *   第 7 条：区分「策略拒绝」和「写错了」—— 前者不要绕，后者才要改。
     *   第 8 条：防止模型看到空结果就下结论"你没有记账"，它应该先怀疑自己条件写错了。
     */
    private static final String SYSTEM_PROMPT = buildSystemPrompt();

    /**
     * 构造系统提示词。
     *
     * 【为什么当前日期必须由程序注入，而不是让模型自己去查】
     *   实测出现过：SQL 用 CURDATE() 正确筛出了 2026-09 的数据，
     *   但模型在自然语言回答里写成了「2024年12月」—— 因为它的训练数据里
     *   没有"今天"，就顺手编了一个。这类错误最难受：数字是对的，
     *   用户稍不注意就被误导，而模型自己毫无察觉。
     *
     *   修法有两种：
     *     a) 让模型每次先查一次 SELECT CURDATE()  ← 多一次付费调用
     *     b) 由程序把日期写进系统提示词            ← 零成本、零延迟 ★采用
     *   日期在服务端一次调用就能拿到，没有任何理由让模型再花一次往返去问。
     *
     *   注意这里写在 buildSystemPrompt() 里而不是硬编码字符串：
     *   日期必须是**运行时**计算的，否则明天跑起来还是昨天的日期。
     */
    private static String buildSystemPrompt() {
        LocalDate today = LocalDate.now();
        String[] weekdays = {"一", "二", "三", "四", "五", "六", "日"};
        String weekday = weekdays[today.getDayOfWeek().getValue() - 1];

        return """
                你是一个个人财务数据助手，可以查询 MySQL 数据库来回答用户关于记账、支出、账户的问题。

                今天是 %s（星期%s）。

                工作流程：
                1. 先调用 getRelevantSchema 获取与当前问题相关的表结构（同一轮对话中了解过就不必重复调用）
                2. 根据表结构写出一条 MySQL SELECT 查询
                3. 调用 executeReadOnlySql 执行这条查询
                4. 用自然语言总结查询结果，给出具体数字

                必须遵守的规则：
                - 只能生成 SELECT 查询，任何写操作都会被系统拒绝
                - 涉及「上个月」「本月」「这个月」「最近 N 天」这类相对时间时：
                  时间范围必须在 SQL 里用 CURDATE() / DATE_SUB() / DATE_FORMAT() 等数据库函数计算，
                  不要在脑子里推算年月。回答里若要说明区间，也必须以 SQL 实际筛出的区间为准，
                  绝不允许自己编造年份或月份
                - 如果 getRelevantSchema 返回的表不足以回答问题（例如缺少要 JOIN 的表），
                  可以调用 getFullSchema 获取全部表结构，但不要默认使用它
                - t_transaction.status 字段的值是中文「收入」或「支出」，不要用数字编码
                - t_transaction.amount 永远是正数，靠 status 区分方向。计算净收入必须写成：
                  SUM(CASE WHEN status = '收入' THEN amount ELSE -amount END)
                - 如果工具返回错误，请阅读 errorType 和 hint 两个字段，按提示修正后重新调用工具，最多重试 2 次
                - 如果 errorType 是 POLICY_REJECTED，说明这是安全策略拒绝而不是 SQL 写法问题。
                  不要试图改写 SQL 绕过它，应直接如实告知用户该操作不被允许
                - 如果查不到数据，先检查筛选条件是否写对，不要直接回答「没有数据」
                """.formatted(today, weekday);
    }

    /** 工具定义：这是模型能看到的「能力清单」，决定了它能做什么 */
    private static final ArrayNode TOOLS = buildTools();

    private final LlmClient llm;
    private final ArrayNode messages = M.createArrayNode();
    private int totalTokens = 0;

    /**
     * 本轮（一次 ask）里发生过的全部工具调用。
     *
     * 【为什么要把它暴露出来】
     *   第 8 步要做评测，就必须能拿到"Agent 到底干了什么"，而不是只看它最后说了什么。
     *   只看自然语言答案没法客观判分（"大约七千"和"7,053.00"哪个对？），
     *   但**SQL 的执行结果是可判定的** —— 拿它和预先写好的黄金 SQL 结果比对即可。
     *   所以这里把工具调用记录下来，作为评测的数据来源。
     *
     *   每次 ask() 开始时清空，语义是"这一问的工具调用"。
     */
    public record ToolCall(String tool, String arguments, String result) {
    }

    /** 注意不要叫 toolCalls —— ask() 里有个局部变量已经叫这个名（模型返回的 tool_calls） */
    private final List<ToolCall> recordedCalls = new ArrayList<>();

    public List<ToolCall> toolCalls() {
        return List.copyOf(recordedCalls);
    }

    /**
     * 当前正在回答的问题。
     *
     * 【为什么用字段而不是让模型把问题再传一遍】
     *   Schema 召回需要拿"用户的原始问题"去匹配。
     *   有两种做法：
     *     a) 工具带一个 question 参数，让模型把用户的话回填一遍  ← 不采用
     *     b) Agent 自己记住当前问题，工具无参数，内部取用      ← 采用
     *
     *   a 的问题是：模型可能**改写**问题（它会习惯性"优化"措辞），
     *   而一旦改写，召回用的就不是用户原话了，效果不可控、也难排查。
     *   b 保证召回输入永远是原话 —— 这也是"别让模型做它不该管的事"的体现。
     */
    private String currentQuestion = "";

    public FinanceAgent(LlmClient llm) {
        this.llm = llm;
        messages.add(message("system", SYSTEM_PROMPT));
    }

    /** 问一个问题，返回最终答案。中间的工具调用过程会打印到控制台 */
    public String ask(String question) throws Exception {
        currentQuestion = question == null ? "" : question;
        recordedCalls.clear();
        messages.add(message("user", question));

        // 自愈护栏用的状态：上一个错误指纹 + 它连续出现的次数
        String lastError = null;
        int sameErrorCount = 0;

        for (int step = 1; step <= MAX_STEPS; step++) {
            LlmClient.Reply reply = llm.chat(messages, TOOLS);
            totalTokens += reply.totalTokens();

            ObjectNode assistant = reply.message();
            JsonNode toolCalls = assistant.path("tool_calls");

            // 模型没有再要求调工具 → 这就是最终答案，本轮结束
            if (!toolCalls.isArray() || toolCalls.isEmpty()) {
                messages.add(assistant);
                System.out.println("  [本轮 token] " + reply.totalTokens()
                        + "，累计 " + totalTokens);
                return assistant.path("content").asText("");
            }

            // 有工具调用 → 先把这条 assistant 消息写回历史，
            // 否则下一步的 tool 消息找不到对应的 tool_call_id，接口会报 400
            messages.add(assistant);

            for (JsonNode call : toolCalls) {
                String callId = call.path("id").asText();
                String toolName = call.path("function").path("name").asText();
                String arguments = call.path("function").path("arguments").asText();

                System.out.println("  [第 " + step + " 轮] 模型选择工具：" + toolName);
                System.out.println("             参数：" + Text.truncate(arguments, 170));

                String result = dispatch(toolName, arguments);
                System.out.println("             返回：" + Text.truncate(result, 170));

                recordedCalls.add(new ToolCall(toolName, arguments, result));
                messages.add(toolMessage(callId, result));

                // ---- 自愈护栏：同一个错误反复出现，说明模型卡住了，继续试只是烧钱 ----
                String signature = errorSignature(result);
                if (signature == null) {
                    lastError = null;
                    sameErrorCount = 0;
                } else {
                    if (signature.equals(lastError)) {
                        sameErrorCount++;
                    } else {
                        lastError = signature;
                        sameErrorCount = 1;
                    }
                    if (sameErrorCount >= MAX_SAME_ERROR) {
                        System.out.println("  [护栏] 同一错误已连续出现 " + sameErrorCount
                                + " 次，提前终止以免无效重试");
                        return "（同一错误反复出现，已停止尝试。最后遇到的错误："
                                + Text.truncate(signature, 160) + "）";
                    }
                }
            }
        }
        return "（已连续调用 " + MAX_STEPS + " 轮工具仍未得出结论，已中止）";
    }

    /**
     * 从工具返回值里提取「错误指纹」，用于判断两次失败是不是同一个原因。
     *
     * 指纹 = errorType + error 文本。之所以要取出 errorType 一起比，
     * 是因为同一个 errorType 下不同 SQL 的报错文本可能不同（比如换了表名），
     * 只比文本会把「换了写法的重复错误」漏判掉。
     *
     * @return null 表示这次调用成功（不是错误），调用方据此重置计数
     */
    private static String errorSignature(String toolResult) {
        if (toolResult == null || !toolResult.contains("\"errorType\"")) {
            return null;
        }
        try {
            JsonNode node = M.readTree(toolResult);
            return node.path("errorType").asText("UNKNOWN") + " | " + node.path("error").asText("");
        } catch (Exception e) {
            return null;
        }
    }

    public int totalTokens() {
        return totalTokens;
    }

    /**
     * 工具分发：把模型点名的工具映射到真正的 Java 方法。
     *
     * 【注意这里把所有异常都吃掉了】
     *   工具自己出错也必须以「结果」的形式返回，不能让异常穿出去。
     *   因为一旦抛出异常，整个 Agent 循环就崩了，模型永远没机会看到错误、也就没机会修正。
     */
    private String dispatch(String toolName, String argumentsJson) {
        try {
            switch (toolName) {
                // 召回版：只返回与当前问题相关的表（第 7 步新增）
                case "getRelevantSchema":
                    return SchemaTool.describeRelevantSchema(currentQuestion);

                // 全量版：逃生舱。召回不可能 100% 准，必须留一条退路
                case "getFullSchema":
                    return SchemaTool.describeSchema();

                case "executeReadOnlySql": {
                    JsonNode args = M.readTree(argumentsJson);
                    return SqlTool.executeReadOnlySql(args.path("sql").asText());
                }

                default:
                    return error("未知工具：" + toolName);
            }
        } catch (Exception e) {
            return error("工具执行异常：" + e.getMessage());
        }
    }

    // ---------- 消息构造 ----------

    private static ObjectNode message(String role, String content) {
        ObjectNode node = M.createObjectNode();
        node.put("role", role);
        node.put("content", content);
        return node;
    }

    private static ObjectNode toolMessage(String toolCallId, String content) {
        ObjectNode node = M.createObjectNode();
        node.put("role", "tool");
        node.put("tool_call_id", toolCallId);
        node.put("content", content);
        return node;
    }

    private static String error(String message) {
        ObjectNode node = M.createObjectNode();
        node.put("error", message == null ? "未知错误" : message.replace("\"", "'"));
        return node.toString();
    }

    // ---------- 工具定义 ----------

    /**
     * 工具定义。
     *
     * 【第 7 步的变化：一个 schema 工具拆成两个】
     *   原先是单一的 getDatabaseSchema（全量）。
     *   现在拆成「召回版 + 全量版」，因为它们的语义完全不同：
     *
     *     getRelevantSchema  默认路径，只返回相关的表  → 省 token、少干扰
     *     getFullSchema      逃生舱，返回全部表        → 召回不准时的退路
     *
     *   为什么不合成一个工具、加个 boolean 参数？
     *   因为**工具的 description 就是给模型的提示词**。
     *   两个工具名 + 两句清楚的 description，比一个带开关的工具更容易让模型选对：
     *   模型看到 getFullSchema 的描述里写着"仅在确实不够用时才调用"，
     *   就会把它当成例外手段，而不是默认选择。
     */
    private static ArrayNode buildTools() {
        String json = """
                [
                  {
                    "type": "function",
                    "function": {
                      "name": "getRelevantSchema",
                      "description": "获取与用户当前问题相关的表结构（表名、字段名、类型、注释、字段取值范围）。在编写任何 SQL 之前，必须先调用本工具了解表结构。它会自动根据用户的问题检索出相关的表。",
                      "parameters": {
                        "type": "object",
                        "properties": {}
                      }
                    }
                  },
                  {
                    "type": "function",
                    "function": {
                      "name": "getFullSchema",
                      "description": "获取数据库中全部表的结构。仅当 getRelevantSchema 返回的表确实不足以回答问题时才调用；一般情况下优先使用 getRelevantSchema。",
                      "parameters": {
                        "type": "object",
                        "properties": {}
                      }
                    }
                  },
                  {
                    "type": "function",
                    "function": {
                      "name": "executeReadOnlySql",
                      "description": "执行一条只读的 MySQL SELECT 查询并返回结果集。只允许 SELECT，任何写操作都会被拒绝。",
                      "parameters": {
                        "type": "object",
                        "properties": {
                          "reason": {
                            "type": "string",
                            "description": "用一句话说明为什么需要这条查询（便于人工核查，也是你的思考过程）"
                          },
                          "sql": {
                            "type": "string",
                            "description": "要执行的 MySQL SELECT 语句"
                          }
                        },
                        "required": ["reason", "sql"]
                      }
                    }
                  }
                ]
                """;
        try {
            return (ArrayNode) M.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("工具定义解析失败", e);
        }
    }
}
