package com.jiang.financeagent.graph;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.jiang.financeagent.agent.LlmClient;
import com.jiang.financeagent.metric.MetricSet;
import com.jiang.financeagent.rag.SchemaChunk;
import com.jiang.financeagent.tool.SchemaTool;
import com.jiang.financeagent.tool.SqlGuard;
import com.jiang.financeagent.tool.SqlTool;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 第 9 步：把「手写主循环」重构成「节点 + 条件边」的图。
 *
 * ============================================================
 * 一、为什么值得重构 —— 手写版的结构性弱点
 * ============================================================
 *   第 4 步的 FinanceAgent 能跑，流程却是藏在 Java 的 for + if 里的：
 *
 *     for (step...) { 问模型 → 解析 tool_calls → 执行 → 回灌 }
 *
 *   这有三个问题，而且是结构性的，不是代码风格问题：
 *     1. **看不见**：想知道"这次问答走了哪几步"，只能翻日志。
 *     2. **测不了**：想单独验证"SQL 被拒之后会不会重试"，得把整个循环跑起来。
 *     3. **插不进钩子**：想在"执行 SQL 前"加人工确认、加审计日志，只能改循环内部。
 *
 *   图编排把流程变成**数据**：节点是数据、边是数据。
 *   于是流程可以画出来、可以逐节点单测、可以在任意节点挂拦截器。
 *
 * ============================================================
 * 二、两种范式的本质区别（面试最容易被问的点）
 * ============================================================
 *   |                     | 手写 tool-calling 循环        | 图编排                    |
 *   |---------------------|------------------------------|--------------------------|
 *   | 谁决定走哪一步        | **模型**（它自己选工具、自己决定再试一次）| **代码**（节点与边写死）    |
 *   | 一轮几次模型调用      | 不确定，模型说了算             | 基本确定（每个节点一次）    |
 *   | **token 成本**       | 高：每轮都要重发整段历史+工具定义 | **低：每节点只发最小 prompt** |
 *   | 可观测性             | 靠日志                        | 靠状态轨迹（本类实现了 trace）|
 *   | 可测试性             | 要跑整个循环                   | 每个节点是纯函数，可单测     |
 *   | 灵活性               | 高（模型可能想到你没写的路径）   | 低（只能走你画的边）        |
 *
 *   ★ token 那一行是**实测出来的**，而且推翻了我原来的预期。
 *     同题对比（"上个月支出多少"）：手写版 4391 token / 3310 ms，
 *     图版 1098 token / 1829 ms —— 降了 75% 和 45%。
 *     原因是 tool-calling 的 API 无状态，必须整段重发；
 *     图里每个节点各发各的最小 prompt，不叠加。详见 Step9Main 的注释。
 *
 *   **结论：问数场景应该用图。** 因为回答一个数据问题的步骤是**确定的**
 *   （理解 → 取表结构 → 写 SQL → 校验 → 执行 → 总结），不需要模型即兴发挥；
 *   而每一步都要拦截、要审计、要能复现，这恰恰是图的强项。
 *   反过来，开放式任务（"帮我把这件事办了"）才更适合 tool-calling 循环。
 *
 * ============================================================
 * 三、图结构
 * ============================================================
 *   <pre>
 *   START
 *     ↓
 *   understand ──不可答──→ refuse ──→ END
 *     │可答
 *     ↓
 *   recall        （纯工具调用，不花 token —— 图让它变成一个可独立验证的步骤）
 *     ↓
 *   generate ←───────────────────────┐
 *     ↓                              │
 *   verify ──漏了关键条件──────────────┤ 未超次数 → 回炉
 *     │条件齐全                       │
 *     ↓                              │
 *   validate ──安全校验不通过──────────┤
 *     │通过                           │
 *     ↓                              │
 *   execute ────执行报错───────────────┘
 *     │成功                  ↘ 超过次数 → giveUp → END
 *     ↓
 *   report ──→ END
 *   </pre>
 *
 *   三道关卡各管一件事，互不重叠：
 *     verify   —— 需求对不对（SQL 有没有漏掉问题里的条件）  ← 纯规则，零 token
 *     validate —— 安全不安全（是不是只读、有没有越权）      ← SqlGuard 8 步
 *     execute  —— 跑不跑得起来（数据库报错就回炉重写）      ← 只读账号兜底
 *
 * ============================================================
 * 四、实测踩到的一个坑：CompiledGraph 的状态会跨 invoke 残留
 * ============================================================
 *   框架的 CompiledGraph 内部持有状态，**同一个实例连续 invoke 两次，
 *   第二次能读到第一次写的值**。实测过程：
 *
 *     run1(你好)     -> schema=(无)      ← 走的 direct 分支，正确
 *     run2(支出多少)  -> schema=SCHEMA    ← 走了 schema 节点
 *     run3(你好)     -> schema=SCHEMA    ← 又走 direct 分支，却读到了 run2 的值 ✗
 *
 *   试过传一个全新的 OverAllState 也没用（框架仍然用内部的）。
 *   最终方案：**每次问答现场 build + compile 一次图**。
 *   实测开销 build+compile+invoke = 0.680 ms（200 次平均），
 *   相对一次 2700 ms 的模型调用完全可以忽略。
 *
 *   这条经验很值得在面试里讲：**"框架能跑通"和"框架用对"是两回事**，
 *   而发现它的唯一办法是跑一个隔离性测试，不是读文档。
 */
public final class FinanceGraph {

    // ============================================================
    // 状态 key —— 用一个常量类集中管理，避免各处硬编码字符串拼错
    // ============================================================
    public static final String K_QUESTION = "question";
    public static final String K_ROUTE = "route";         // 条件边读的路由值
    public static final String K_SCHEMA = "schema";       // 召回的表结构
    public static final String K_SQL = "sql";             // 模型生成的 SQL
    public static final String K_FEEDBACK = "feedback";   // 上一轮失败原因（回灌给 generate）
    public static final String K_RESULT = "result";       // SQL 执行结果（JSON）
    public static final String K_ATTEMPTS = "attempts";   // 已尝试次数
    public static final String K_ANSWER = "answer";       // 最终回答
    public static final String K_TRACE = "trace";         // 节点执行轨迹
    public static final String K_VERIFY_NOTE = "verifyNote"; // 需求核对节点的判定结果（排查用）

    /**
     * 对话历史（多轮用）。每条形如「用户：xxx｜回答：yyy」。
     * ★ 由**调用方**维护并传入，图自己不记忆 —— 原因见 ask(String, List) 的注释。
     */
    public static final String K_HISTORY = "history";

    /** 改写后的问题。只有真的改写过才有值，用来在前端展示"系统理解成了什么" */
    public static final String K_REWRITTEN = "rewritten";

    /** 同一个 SQL 最多重试几次（含第一次）。图里的循环必须设上限，否则会转不出来。 */
    private static final int MAX_ATTEMPTS = 3;

    /** 改写时最多带几轮历史。带太多既费 token，又容易把模型带偏。 */
    private static final int MAX_HISTORY_ITEMS = 6;

    private static final String REWRITE_PROMPT = """
            你是多轮对话的「查询改写器」。用户正在和一个财务数据助手对话，
            新问题里可能省略了上下文（例如用「那……呢」「换成上个月」「这个呢」指代前文）。

            你的任务：把「当前问题」改写成一句**脱离上下文也能独立理解**的完整问题。

            规则：
            - 有省略或指代时，从对话历史里补全
            - 当前问题**本身已经完整**时，就**原样返回**，不要画蛇添足
            - 只做改写：不要回答问题、不要添加用户没提到的条件、不要补充解释
            - 只输出改写后的问题本身，不要任何前缀、引号或说明文字

            示例：
              历史：用户问「上个月支出多少」→ 回答「7053 元」
              当前：「那收入呢」          → 上个月收入是多少？
              当前：「换成本月呢」        → 本月支出是多少？
              当前：「餐饮花了多少」      → 餐饮花了多少？
                                                   （已完整，原样返回）
            """;

    private static final String NODE_REWRITE = "rewrite";       // 多轮：把省略/指代补全成独立问题
    private static final String NODE_UNDERSTAND = "understand";
    private static final String NODE_RECALL = "recall";
    private static final String NODE_GENERATE = "generate";
    private static final String NODE_VERIFY = "verify";
    private static final String NODE_VALIDATE = "validate";
    private static final String NODE_EXECUTE = "execute";
    private static final String NODE_REPORT = "report";
    private static final String NODE_REFUSE = "refuse";
    private static final String NODE_GIVE_UP = "giveUp";

    private final LlmClient llm;
    private final boolean verbose;
    private final MetricSet metrics;

    public FinanceGraph(LlmClient llm, boolean verbose) {
        this.llm = llm;
        this.verbose = verbose;
        this.metrics = MetricSet.load();
        if (verbose) {
            // 加载情况要能看见。指标配置读不到时程序**不会报错**（会降级），
            // 所以"静默降级"是这里最危险的失败模式 —— 看起来一切正常，
            // 实际语义层根本没生效。打一行日志，让降级可见。
            System.out.println("[语义层] " + metrics.loadNote());
        }
    }

    // ============================================================
    // 图装配
    // ============================================================

    /**
     * 构建并编译图。
     *
     * 【注意这里每次都新建 —— 不是浪费，是必须】
     *   原因见类注释第四节：CompiledGraph 的状态会跨 invoke 残留，
     *   复用同一个实例会让上一题的表结构漏进下一题。
     *   实测单次构建+编译 0.68 ms，可忽略。
     */
    public CompiledGraph build() throws Exception {
        StateGraph graph = new StateGraph();

        graph.addNode(NODE_REWRITE, AsyncNodeAction.node_async(this::rewrite));
        graph.addNode(NODE_UNDERSTAND, AsyncNodeAction.node_async(this::understand));
        graph.addNode(NODE_RECALL, AsyncNodeAction.node_async(this::recall));
        graph.addNode(NODE_GENERATE, AsyncNodeAction.node_async(this::generate));
        graph.addNode(NODE_VERIFY, AsyncNodeAction.node_async(this::verify));
        graph.addNode(NODE_VALIDATE, AsyncNodeAction.node_async(this::validate));
        graph.addNode(NODE_EXECUTE, AsyncNodeAction.node_async(this::execute));
        graph.addNode(NODE_REPORT, AsyncNodeAction.node_async(this::report));
        graph.addNode(NODE_REFUSE, AsyncNodeAction.node_async(this::refuse));
        graph.addNode(NODE_GIVE_UP, AsyncNodeAction.node_async(this::giveUp));

        // 入口先是 rewrite（多轮改写），再进 understand 判断可答性 ——
        // 顺序不能反：判断"能不能答"必须基于**补全后**的完整问题。
        graph.addEdge(StateGraph.START, NODE_REWRITE);
        graph.addEdge(NODE_REWRITE, NODE_UNDERSTAND);

        // 分支一：问题能不能答 —— 不能答就别浪费后续的模型调用
        graph.addConditionalEdges(NODE_UNDERSTAND, route(),
                Map.of("able", NODE_RECALL, "unable", NODE_REFUSE));

        graph.addEdge(NODE_RECALL, NODE_GENERATE);

        // ★ generate → validate 这条普通边不能省。
        //   我第一版只写了 validate 的条件边（retry 指向 generate），
        //   漏了 generate 自己的出边，运行时报
        //     GraphRunnerException: edge with sourceId: 'generate' doesn't exist!
        //   教训：条件边只定义"从谁出发、按什么条件去哪"，
        //        **节点自己的默认去向仍然要单独声明**。图是"节点 + 出边"的组合，
        //        缺一个出边，节点在运行时就等于走投无路。
        graph.addEdge(NODE_GENERATE, NODE_VERIFY);

        // 需求覆盖检查：SQL 有没有漏掉问题里的关键条件？漏了就回炉
        graph.addConditionalEdges(NODE_VERIFY, route(),
                Map.of("ok", NODE_VALIDATE, "retry", NODE_GENERATE, "giveup", NODE_GIVE_UP));

        // 分支二：校验不通过 —— 未超次数就回 generate 重写，超了就放弃
        graph.addConditionalEdges(NODE_VALIDATE, route(),
                Map.of("ok", NODE_EXECUTE, "retry", NODE_GENERATE, "giveup", NODE_GIVE_UP));

        // 分支三：执行报错 —— 同样回 generate 重写（把报错原文带回去）
        graph.addConditionalEdges(NODE_EXECUTE, route(),
                Map.of("ok", NODE_REPORT, "retry", NODE_GENERATE, "giveup", NODE_GIVE_UP));

        graph.addEdge(NODE_REPORT, StateGraph.END);
        graph.addEdge(NODE_REFUSE, StateGraph.END);
        graph.addEdge(NODE_GIVE_UP, StateGraph.END);

        return graph.compile();
    }

    /**
     * 条件边：统一读 K_ROUTE。
     *
     * 【为什么三个条件边都用同一个 key 而不是各用一个】
     *   因为它们不会同时生效 —— 每条边只在它上游节点执行完之后被求值一次，
     *   而上游节点一定会先把 K_ROUTE 写成自己想要的值。
     *   共用同一个 key 让状态字段表更短，也少三处拼错字符串的机会。
     */
    private AsyncEdgeAction route() {
        return AsyncEdgeAction.edge_async(state -> String.valueOf(state.value(K_ROUTE).orElse("")));
    }

    /** 跑一次问答（单轮）。为了状态隔离，每次都新建图（见 build() 注释）。 */
    public Map<String, Object> ask(String question) throws Exception {
        return ask(question, List.of());
    }

    /**
     * 跑一次问答（支持多轮）。
     *
     * ============================================================
     * 为什么 history 由调用方维护，而不是让图自己记住
     * ============================================================
     *   因为图**每次都是新建的** —— 这是第 9 步那个坑逼出来的设计：
     *   CompiledGraph 的状态会跨 invoke 残留，所以必须每次现场 build。
     *   既然图天然无状态，"记住上一轮"就只能放在外面。
     *
     *   这反而更干净，职责划分很清楚：
     *     · **隔离**靠「每次新建图」
     *     · **记忆**靠「调用方显式传入」
     *   两者互不干扰 —— 不会出现"想清空记忆却清不掉"的情况。
     *   （对比一下：如果让图内部持有历史，那"新会话"和"继续上一轮"
     *     就得靠额外的开关来区分，复杂度立刻上去了。）
     *
     * @param history 之前的对话摘要，每条形如「用户：xxx｜回答：yyy」。
     *                传 null 或空列表等价于单轮提问。
     */
    public Map<String, Object> ask(String question, List<String> history) throws Exception {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put(K_QUESTION, question);
        input.put(K_ATTEMPTS, 0);
        input.put(K_TRACE, new ArrayList<String>());
        input.put(K_HISTORY, new ArrayList<>(history == null ? List.of() : history));

        return build().invoke(input)
                .map(OverAllState::data)
                .orElse(Map.of());
    }

    // ============================================================
    // 节点实现
    // ============================================================

    /**
     * 节点 0：查询改写 —— 多轮对话的入口。
     *
     * ============================================================
     * 为什么单独做一个节点，而不是把历史塞进 generate 的提示词
     * ============================================================
     *   两个理由：
     *
     *   1) **职责单一**。generate 已经在同时处理「时间 + 收支方向 + 分类 + 选表」，
     *      第 10 步实测证明它总会漏掉其中一项。再往里塞"理解指代"，
     *      只会让它漏得更多。把「把问题说清楚」从「把问题翻译成 SQL」里拆出来，
     *      每个节点只做一件事。
     *
     *   2) **可观测**。改写结果会写进 K_REWRITTEN 并随 trace 返回，
     *      前端能直接显示"系统把「那收入呢」理解成了「上个月收入多少」"。
     *      指代消解错了的时候，一眼就能看出来 ——
     *      否则这个错误会一路传到 SQL 甚至最终答案才暴露，那时候已经很难定位了。
     *
     * ============================================================
     * ★ 零成本的关键：没有历史时完全不调用模型
     * ============================================================
     *   单轮提问（包括全部 26 条评测用例）根本没有上下文需要补全。
     *   所以下面第一件事就是判断 history.isEmpty() 并直接返回，
     *   **一次模型调用都不花**。
     *   这保证了本次改动对单轮场景是纯零影响 —— 也是我敢在投递前一天动它的原因。
     */
    private Map<String, Object> rewrite(OverAllState state) throws Exception {
        String question = str(state, K_QUESTION);
        List<String> history = historyOf(state);

        if (history.isEmpty()) {
            log(NODE_REWRITE, "无对话历史，跳过改写（零调用）");
            return updates(state, NODE_REWRITE, K_ROUTE, "");
        }

        String rewritten = cleanRewrite(llm.complete(REWRITE_PROMPT, """
                【对话历史】
                %s

                【当前问题】
                %s
                """.formatted(String.join("\n", recent(history)), question)));

        // 模型认为问题已经完整 → 不改写，也不写 K_REWRITTEN
        // （这样前端就不会多显示一行"改写"，避免噪音）
        if (rewritten.isBlank() || rewritten.equals(question)) {
            log(NODE_REWRITE, "问题已完整，保持原样：" + question);
            return updates(state, NODE_REWRITE, K_ROUTE, "");
        }

        log(NODE_REWRITE, "改写：" + question + "  →  " + rewritten);
        return updates(state, NODE_REWRITE,
                K_ROUTE, "",
                K_QUESTION, rewritten,      // ★ 覆盖 K_QUESTION，后续所有节点都基于它
                K_REWRITTEN, rewritten);
    }

    /**
     * 节点 1：意图理解。
     *
     * 【为什么值得单独一个节点】
     *   这道闸门能挡掉两类浪费：
     *     - 与财务无关的问题（"今天天气怎么样"）→ 后面 4~5 次模型调用全省了
     *     - 需要写操作的问题（"帮我删掉记录"）  → 提前声明做不到，而不是撞到护栏才说
     *   用一次便宜的短调用，换掉后面几次调用，是划算的。
     */
    private Map<String, Object> understand(OverAllState state) throws Exception {
        String question = str(state, K_QUESTION);

        String verdict = llm.complete("""
                你是一个问题分类器。判断用户的问题能否通过查询个人记账数据库来回答。

                库里只有这些内容：交易记录（收入/支出、金额、时间、分类、账户）、
                账户信息与余额、收支分类、预算类型。系统是只读的，不能修改任何数据。

                以下属于「可答」：
                - 上个月支出多少
                - 餐饮类一共花了多少钱
                - 我有几个账户 / 各账户余额是多少
                - 收入减掉支出还剩多少
                - 哪个月花得最多

                以下属于「不可答」：
                - 今天天气怎么样（与记账无关）
                - 帮我把所有记录删掉（需要写操作，系统只读）
                - 帮我写一首诗（与记账无关）

                只输出两个字：可答 或 不可答。不要输出任何其他文字、标点或解释。
                """, question);

        // 先判「不可答」：因为"不可答"三个字里也包含"可答"，
        // 顺序反了会把所有拒绝都判成可答 —— 这是个很容易踩的字符串包含陷阱。
        boolean able = !verdict.contains("不可答") && verdict.contains("可答");

        log("understand", "问题=" + question + " → 模型判定=" + verdict.trim() + " → " + (able ? "可答" : "不可答"));

        return updates(state, NODE_UNDERSTAND,
                K_ROUTE, able ? "able" : "unable");
    }

    /** 节点 2：Schema 召回。纯工具调用，零 token —— 图让它成为可独立验证的一步。 */
    private Map<String, Object> recall(OverAllState state) throws Exception {
        String question = str(state, K_QUESTION);
        String schema = SchemaTool.describeRelevantSchema(question);

        // 只打第一行的摘要，别把整段表结构刷屏
        log("recall", "召回到 " + firstLine(schema));

        return updates(state, NODE_RECALL,
                K_ROUTE, "",
                K_SCHEMA, schema);
    }

    /**
     * 节点 3：生成 SQL。
     *
     * 【这里是循环的入口，所以 attempts 在这里 +1】
     *   把"计数"放在循环入口而不是出口，保证无论从哪条边回来都只计一次。
     *
     * ============================================================
     * 第 13 步：业务口径不再写在提示词里
     * ============================================================
     *   改之前，这个方法里的提示词有这样一段自然语言规则：
     *     「问『支出多少』→ WHERE status='支出' 之后 SUM(amount)……
     *       **只有**问『净额 / 结余 / 还剩多少』时，才用 CASE WHEN 那个式子」
     *
     *   它有三个结构性缺陷：
     *     1. 用户说法和口径不是一一对应 —— 问「我赚了多少」这句，
     *        它既没匹配到「支出」也没匹配到「结余」，只能靠模型猜
     *     2. 改口径要动 Java 代码 + 重新编译
     *     3. 没有单一事实来源：口径散在几百字的提示词里
     *
     *   现在改成从 config/metrics.json 读，**命中项直接注入表达式**：
     *     指标定义、别名词表、是否适用 —— 全是配置；匹配是纯规则（零 token）；
     *     generate 从"理解业务口径"降级为"把已定好的表达式填进 SQL"。
     *
     *   注意 metricsBlock 在**没命中时也不是空的** —— 它会换成一段最小兜底说明，
     *   保证"关掉语义层"等价于"改动前行为"（A/B 对比才有意义）。
     */
    private Map<String, Object> generate(OverAllState state) throws Exception {
        int attempts = intVal(state, K_ATTEMPTS) + 1;
        String question = str(state, K_QUESTION);
        String schema = str(state, K_SCHEMA);
        String feedback = str(state, K_FEEDBACK);

        String feedbackBlock = feedback.isBlank()
                ? "（这是第一次尝试）"
                : "上一次的尝试失败了，原因是：\n" + feedback + "\n请针对这个原因修正后再输出一条 SQL。";

        // 命中的业务口径（纯规则匹配，零 token）。
        // 没命中时返回一段最小兜底说明，保证"关掉语义层"等价于改动前的行为。
        String metricsBlock = metrics.renderForPrompt(question);

        String sql = cleanSql(llm.complete("""
                你是 MySQL 专家。根据给定的表结构，把用户问题写成一条 SELECT 查询。

                硬性要求：
                - 只能写 SELECT，任何写操作都会被系统拒绝
                - 只输出 SQL 本身：不要解释、不要 markdown 代码块、不要以分号结尾
                - **只回答用户问的东西**，这条要分两半看，很容易做过头：
                  · 用户问题里提到的筛选条件**必须**落到 WHERE 里。
                    比如问「餐饮花了多少钱」，就必须 JOIN 分类表并加
                    `c.category_name = '餐饮美食'` —— 少了这个条件，查出来的
                    就是「所有支出」而不是「餐饮支出」，答案完全不同。
                  · 反过来，**不要**额外增加用户没问的分组维度或输出列。
                    问「上个月支出多少」就给一个总数，不要按分类拆开。
                - 时间条件的**使用条件**（这一条最容易错，务必先判断）：
                  · **只有当问题里出现「上个月 / 本月 / 这个月 / 最近 N 天 / 今年」这类
                    相对时间词时，才加 create_time 筛选。**
                  · 问题里**没有提到任何时间**（例如「餐饮美食一共花了多少钱」、
                    「收入减掉支出还剩多少」），就**绝对不要**加时间条件 ——
                    加了会把全量数据错误地缩小到一个区间，答案直接错。
                  · 需要加时间时，区间已经由系统算好（见下方「时间条件参考」），
                    直接照抄对应的具体日期即可，不要自己用 CURDATE / DATE_SUB 推算。
                - t_transaction.status 的值是中文「收入」或「支出」，不要用数字编码
                - t_transaction.amount 恒为正数，方向由 status 决定
                - 需要多表时显式写出 JOIN ... ON
                """, """
                表结构：
                %s

                【时间条件参考】（**仅当问题里出现相对时间词时才使用**）
                %s

                %s

                用户问题：%s

                %s
                """.formatted(schema, timeReference(), metricsBlock, question, feedbackBlock)));

        log("generate", "第 " + attempts + " 次生成 SQL：" + oneLine(sql));

        return updates(state, NODE_GENERATE,
                K_ROUTE, "",
                K_ATTEMPTS, attempts,
                K_SQL, sql,
                K_FEEDBACK, "");
    }

    /**
     * 节点 4：需求覆盖检查 —— SQL 有没有漏掉问题里的关键条件？
     *
     * ============================================================
     * 为什么要加这个节点：一个反复出现的失败模式
     * ============================================================
     *   只靠提示词让 generate 一步到位，实测会**反复漏条件**，而且漏哪一项是随机的：
     *
     *     "上个月支出多少"   → 时间对了、方向对了 ✓
     *     "这个月支出多少"   → 时间写成上个月 ✗（提示词加警告后，又矫枉过正）
     *     "上个月的收入是多少" → 时间对了，但方向写成了「支出」✗
     *
     *   规律很清楚：**一个节点要同时处理「时间 + 收支方向 + 分类 + 选表」，
     *   总有一项会被漏掉**。这不是提示词写得不够好，是单步任务的复杂度问题。
     *   而且我试过加 ★ 警告来纠正某一项，结果只是把错误推向另一项 —— 打地鼠。
     *
     * ============================================================
     * 解法：把检查拆成独立节点，而且用**纯规则**而不是再问一次模型
     * ============================================================
     *   关键判断：**这个检查能用规则做，就不该用模型做。**
     *
     *   | 方式           | 成本        | 可靠性                        |
     *   |---------------|------------|------------------------------|
     *   | 让模型自检      | +1 次调用   | 仍可能漏（它刚刚才漏过一次）        |
     *   | **规则检查**    | **零 token** | 对「词是否出现」这类判断是确定性的 ★ |
     *
     *   模型漏掉的是"SQL 里有没有出现『收入』这两个字"—— 这是一个字符串判断，
     *   交给 Java 一毫秒就做完了，没有任何理由再花一次模型调用去问它一遍。
     *   （这不代表规则能代替模型：判断"SQL 语义对不对"仍然要靠模型，
     *     但那件事的成本和难度，和"有没有漏关键词"完全不是一个量级。）
     *
     *   这道检查能覆盖到：收支方向、时间区间、分类条件 —— 恰好就是实测漏得最多的三类。
     */
    private Map<String, Object> verify(OverAllState state) {
        String question = str(state, K_QUESTION);
        String sql = str(state, K_SQL);
        int attempts = intVal(state, K_ATTEMPTS);

        List<String> missing = missingConstraints(question, sql);
        // 诊断信息带上问题原文和判定依据 —— 排查"规则该拦没拦"这类问题时，
        // 光看"通过/不通过"是不够的，必须能看到规则读到的输入是什么。
        String note = (missing.isEmpty() ? "通过" : "缺：" + String.join("；", missing))
                + " ｜问题[" + question + "] 长度" + question.length()
                + " ｜WHERE含create_time=" + whereClause(sql).contains("create_time")
                + " ｜SQL含GROUP BY=" + upperSql(sql).contains("GROUP BY");

        if (missing.isEmpty()) {
            log("verify", "问题里的关键条件都已落到 SQL 里");
            return updates(state, NODE_VERIFY, K_ROUTE, "ok", K_VERIFY_NOTE, note);
        }

        boolean exhausted = attempts >= MAX_ATTEMPTS;
        log("verify", "SQL 漏了条件：" + note + (exhausted ? " → 放弃" : " → 回炉重写"));

        return updates(state, NODE_VERIFY,
                K_ROUTE, exhausted ? "giveup" : "retry",
                K_VERIFY_NOTE, note,
                K_FEEDBACK, "你生成的 SQL 漏掉了问题里的关键条件：" + note
                        + "。请把这些条件补进 WHERE（或 JOIN 的筛选）里后重新输出 SQL。");
    }

    /**
     * 把问题里的关键约束，与 SQL 文本做一次对照。
     *
     * ============================================================
     * ⚠️ 这套规则的第一版把三类**正确**的 SQL 误判成了错误
     * ============================================================
     *   给「图编排版」补上评测覆盖之后（`Step8Main --graph`），
     *   立刻暴露了 4 个失败用例，根因全部在这一个方法里 —— 规则写得太粗：
     *
     *   1. **只看 `sql.contains("create_time")`，不区分它出现在哪儿**
     *      「每个月的支出分别是多少？」的正确 SQL 是
     *        SELECT DATE_FORMAT(create_time,'%Y-%m'), SUM(amount) ... GROUP BY ...
     *      这里的 create_time 是**分组维度**，却被当成"乱加时间筛选"拦下。
     *      → 修复：只看 **WHERE 子句**里的 create_time。
     *
     *   2. **时间词识别不到「8 月」「2026 年 3 月」这类具体时间**
     *      「8 月的支出比 9 月多多少？」明明有明确时间，规则却说"没提到时间"。
     *      → 修复：用 `\d+\s*月` / `\d{4}\s*年` 识别具体月份与年份。
     *
     *   3. **只拦"多加了"，不拦"少加了"**
     *      「2026 年 3 月我花了多少钱？」漏了时间条件，
     *      算出来是全部支出（34504）而不是 3 月的（2950），规则却没拦住。
     *      → 修复：问题提到**具体**时间时，WHERE 里必须真的有时间条件。
     *
     *   ★ 这件事本身是个教训：**规则检查的"粒度"要和语义对齐**。
     *     我当初用「词有没有出现」这种粗粒度去近似「筛选条件有没有写对」，
     *     在简单问题上碰巧都对，一旦遇到"同一个字段出现在不同位置"
     *     就立刻失效 —— 而这类失效在手工样例里根本看不出来。
     *
     * ============================================================
     * 其它设计
     * ============================================================
     *   - 分类名不硬编码，从 SchemaTool 的 chunk 里取 ——
     *     **复用第 7 步建好的 schema 索引**，往库里加新分类这里自动生效。
     *   - 时间区间不在这里计算：区间已经由程序算好直接交给模型（见 timeReference），
     *     这里只做"有没有 / 对不对"的核对。
     */
    private static List<String> missingConstraints(String question, String sql) {
        List<String> missing = new ArrayList<>();
        String upper = upperSql(sql);
        String where = whereClause(sql);

        // ---- ① 收支方向：问题提到就必须落到 SQL 里 ----
        // ★ 但"净额式"写法必须豁免。
        //   SUM(CASE WHEN status = '收入' THEN amount ELSE -amount END)
        //   这个表达式用**减法**表达了方向，里面根本不会出现「支出」二字 ——
        //   可两个方向其实都被它覆盖了。不豁免的话，一条完全正确的 SQL
        //   会被判成"漏了支出条件"，然后进入注定失败的重试循环
        //   （实测 D17「收入减掉支出还剩多少」就是这么挂的）。
        //
        //   这是"字面检查"第三次栽在同一个坑上：
        //     · 第 1 次：只看 create_time 有没有出现，不管它在 WHERE 还是 GROUP BY
        //     · 第 2 次：识别不到「8 月」这类具体月份写法
        //     · 第 3 次（本次）：语义层把口径换成 -amount 之后，
        //       "SQL 里有没有『支出』二字"这个判据失效了
        //   规律很清楚：**规则只要依赖字面，就会在"同一个意思换种写法"时失效**。
        //   所以每次给 SQL 的表达方式增加自由度（比如这次引入指标表达式），
        //   都得回头把依赖字面的规则再过一遍。
        boolean netStyle = upper.matches("(?s).*-\\s*AMOUNT.*");

        if (!netStyle && question.contains("收入") && !sql.contains("收入")) {
            missing.add("问题提到「收入」，但 SQL 里没有收入相关的条件或计算");
        }
        if (!netStyle && question.contains("支出") && !sql.contains("支出")) {
            missing.add("问题提到「支出」，但 SQL 里没有支出相关的条件或计算");
        }

        // ---- ② 分类条件：问题提到某个分类名就必须带上 ----
        for (String category : categoryNames()) {
            if (question.contains(category) && !sql.contains(category)) {
                missing.add("问题提到分类「" + category + "」，但 SQL 里没有这个分类条件");
            }
        }

        // ---- ③ 按月拆分：问题问「每个月」，SQL 就必须有 GROUP BY ----
        if (mentionsMonthly(question) && !upper.contains("GROUP BY")) {
            missing.add("问题问的是按月拆分，但 SQL 里没有 GROUP BY —— 那样只会返回一个总数");
        }

        // ---- ④ 时间条件的「有无」必须与问题一致（★ 只看 WHERE，不看 GROUP BY）----
        // 这条是被一次严重事故逼出来的：把「时间条件参考」放进提示词之后，
        // 模型把它当成了**默认值**，导致「餐饮美食一共花了多少钱」这种
        // 完全没问时间的问题也被加上 9 月筛选 —— 答案从 4096 变成 983。
        // 教训：**往提示词里放参考信息，模型会把它当默认值用**，
        //       所以必须同时给出「什么时候不该用」的规则，并且用代码兜住。
        boolean hasTimeFilter = where.contains("create_time");
        boolean asksTime = mentionsTime(question);

        if (!asksTime && hasTimeFilter) {
            missing.add("问题里没有提到时间范围，但 SQL 的 WHERE 里加了 create_time 筛选。"
                    + "这会把全量数据错误地限制在一个区间内，请去掉时间条件");
        }
        if (asksTime && mentionsConcreteTime(question) && !hasTimeFilter) {
            missing.add("问题里提到了具体时间（如「8 月」「2026 年 3 月」），"
                    + "但 SQL 的 WHERE 里没有任何时间条件 —— 会算出全量，而不是那个时间段的数");
        }

        // 提到相对时间的话，还要核对用的是不是**正确的那个区间**。
        // 这两条能抓住"问本月却筛了上个月"这类错误 —— 光看有没有 create_time 是抓不到的。
        if ((question.contains("上个月") || question.contains("上月"))
                && !sql.contains(lastMonthStart().toString())) {
            missing.add("问题问的是「上个月」，SQL 的时间区间起点应当是 " + lastMonthStart());
        }
        if ((question.contains("本月") || question.contains("这个月") || question.contains("当月"))
                && !sql.contains(thisMonthStart().toString())) {
            missing.add("问题问的是「本月」，SQL 的时间区间起点应当是 " + thisMonthStart());
        }

        return missing;
    }

    /** SQL 的大写形式，供关键字判断使用（不改变原 SQL 的大小写） */
    private static String upperSql(String sql) {
        return sql == null ? "" : sql.toUpperCase(Locale.ROOT);
    }

    /**
     * 取出 WHERE 子句的内容（截到 GROUP BY / ORDER BY / HAVING / LIMIT 为止）。
     *
     * 【为什么必须单独取出来】
     *   `create_time` 出现在 WHERE 里是**筛选**，出现在 SELECT / GROUP BY 里是**分组维度**。
     *   前者需要核对，后者完全合法。
     *   用一句 `sql.contains("create_time")` 一锅端，就会把「按月份分组」
     *   这类正确 SQL 判成"乱加时间筛选" —— 这是实测踩到的真实误判。
     */
    private static String whereClause(String sql) {
        String upper = upperSql(sql);
        int where = upper.indexOf("WHERE");
        if (where < 0) {
            return "";
        }
        int end = sql.length();
        for (String keyword : new String[]{"GROUP BY", "ORDER BY", "HAVING", "LIMIT"}) {
            int idx = upper.indexOf(keyword, where);
            if (idx > 0 && idx < end) {
                end = idx;
            }
        }
        return sql.substring(where, end);
    }

    /** 问题是否提到了任何时间（相对时间词 + 具体年月） */
    private static boolean mentionsTime(String question) {
        return mentionsConcreteTime(question)
                || question.contains("上个月") || question.contains("上月")
                || question.contains("本月") || question.contains("这个月") || question.contains("当月")
                || question.contains("最近") || question.contains("今年") || question.contains("去年")
                || question.contains("年初") || question.contains("季度");
    }

    /**
     * 问题是否提到**具体**时间（如「8 月」「2026 年 3 月」）。
     * 这类问题必须在 WHERE 里落成条件，漏了就一定算错。
     * 注意「每个月」不含数字，不会被这条匹配到 —— 它是分组需求，走 ③。
     */
    private static boolean mentionsConcreteTime(String question) {
        return question.matches(".*\\d+\\s*月.*") || question.matches(".*\\d{4}\\s*年.*");
    }

    /** 问题是否要求「按月拆分」—— 这类问题必须有 GROUP BY */
    private static boolean mentionsMonthly(String question) {
        return question.contains("每个月") || question.contains("每月") || question.contains("按月")
                || question.contains("各月") || question.contains("逐月");
    }

    /** 从第 7 步的 schema chunk 里取全部分类名（不硬编码，加新分类自动生效） */
    private static List<String> categoryNames() {
        try {
            for (SchemaChunk chunk : SchemaTool.chunks()) {
                if (!"t_category".equals(chunk.table())) {
                    continue;
                }
                for (SchemaChunk.Column column : chunk.columns()) {
                    if ("category_name".equals(column.name())) {
                        return column.values();
                    }
                }
            }
        } catch (Exception e) {
            // 取不到分类名不该让整条问答失败 —— 只是少一道检查而已
            return List.of();
        }
        return List.of();
    }

    /**
     * 节点 5：安全校验（只做路由决策，不做执行）。
     *
     * 【为什么这里调 SqlGuard、而执行时 SqlTool 内部还会再调一次】
     *   这是刻意的职责划分，不是重复：
     *     - 本节点负责**决策**：这条 SQL 能不能放行？不能的话是"改一改就行"还是"根本不允许"？
     *     - SqlTool 负责**执行与兜底**：它必须自己确保安全，不能信任调用方已经校验过。
     *   如果执行入口依赖"上游一定校验过了"，那 SqlTool 就不再是一个安全边界，
     *   将来任何直接调用它的地方都会变成漏洞。
     */
    private Map<String, Object> validate(OverAllState state) {
        String sql = str(state, K_SQL);
        int attempts = intVal(state, K_ATTEMPTS);

        SqlGuard.Verdict verdict = SqlGuard.check(sql);

        if (verdict.allowed()) {
            log("validate", "通过安全检查");
            return updates(state, NODE_VALIDATE, K_ROUTE, "ok");
        }

        // POLICY 类拒绝（写操作等）不该让模型反复改写法去撞 —— 直接放弃更省成本
        boolean policy = verdict.category() == SqlGuard.Category.POLICY;
        boolean exhausted = attempts >= MAX_ATTEMPTS;
        boolean giveUp = policy || exhausted;

        log("validate", "未通过（" + verdict.category() + "）：" + verdict.reason()
                + (giveUp ? " → 放弃" : " → 重写"));

        return updates(state, NODE_VALIDATE,
                K_ROUTE, giveUp ? "giveup" : "retry",
                K_FEEDBACK, "安全检查未通过：" + verdict.reason() + "（类型 " + verdict.category() + "）");
    }

    /** 节点 6：执行 SQL。错误以数据形式回流给 generate，而不是抛出去。 */
    private Map<String, Object> execute(OverAllState state) {
        String sql = str(state, K_SQL);
        int attempts = intVal(state, K_ATTEMPTS);

        String result = SqlTool.executeReadOnlySql(sql);
        boolean failed = result.contains("\"errorType\"");

        if (!failed) {
            log("execute", "执行成功：" + oneLine(result));
            return updates(state, NODE_EXECUTE, K_ROUTE, "ok", K_RESULT, result);
        }

        boolean exhausted = attempts >= MAX_ATTEMPTS;
        log("execute", "执行失败" + (exhausted ? " → 放弃" : " → 重写") + "：" + oneLine(result));

        return updates(state, NODE_EXECUTE,
                K_ROUTE, exhausted ? "giveup" : "retry",
                K_FEEDBACK, "SQL 执行失败，数据库返回：" + result);
    }

    /** 节点 7：把结果翻译成人话。 */
    private Map<String, Object> report(OverAllState state) throws Exception {
        String answer = llm.complete("""
                你是一个个人财务助手。根据已经查到的数据结果，用简洁的中文回答用户的问题。

                要求：
                - 给出具体数字，不要用"大约""可能"这类含糊表述
                - 如果结果为空或为 0，就如实说明，绝对不要编造数字
                - 数据已经查到了，不要再说"我无法访问数据库"这类话
                - 不要输出 SQL，也不要说你执行了什么查询
                - 如果用户问的是时间区间，说明区间时要以 SQL 实际筛出的范围为准
                """, """
                用户问题：%s

                查询结果（JSON）：
                %s
                """.formatted(str(state, K_QUESTION), str(state, K_RESULT)));

        log("report", "生成最终回答");
        return updates(state, NODE_REPORT, K_ANSWER, answer.trim());
    }

    private Map<String, Object> refuse(OverAllState state) {
        String answer = "抱歉，这个问题我没法回答。我只能查询个人记账数据"
                + "（交易记录、账户余额、收支分类、预算类型），而且只能读、不能改。"
                + "如果你想了解自己的收支情况，可以直接问我，比如「上个月支出多少」。";
        log("refuse", "问题不在可答范围内，直接拒绝");
        return updates(state, NODE_REFUSE, K_ANSWER, answer);
    }

    private Map<String, Object> giveUp(OverAllState state) {
        String answer = "抱歉，这个问题我没能查出来。已尝试 " + intVal(state, K_ATTEMPTS)
                + " 次仍未通过校验或执行，最后的原因是：" + str(state, K_FEEDBACK);
        log("giveUp", "已达重试上限，终止");
        return updates(state, NODE_GIVE_UP, K_ANSWER, answer);
    }

    // ============================================================
    // 状态读写工具
    // ============================================================

    /**
     * 组装节点返回值。
     *
     * 【为什么 trace 要在这里统一追加，而不是让每个节点自己写】
     *   轨迹是"横切关注点"——每个节点都要记一笔，但谁都不该关心它的格式。
     *   统一在出口处追加，节点里就只剩业务逻辑。
     */
    private Map<String, Object> updates(OverAllState state, String node, Object... keyValues) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            result.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        result.put(K_TRACE, appendTrace(state, node));
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<String> appendTrace(OverAllState state, String node) {
        Object existing = state.value(K_TRACE).orElse(null);
        List<String> trace = existing instanceof List
                ? new ArrayList<>((List<String>) existing)
                : new ArrayList<>();
        trace.add(node);
        return trace;
    }

    private static String str(OverAllState state, String key) {
        return String.valueOf(state.value(key).orElse(""));
    }

    private static int intVal(OverAllState state, String key) {
        Object value = state.value(key).orElse(0);
        return value instanceof Number number ? number.intValue() : 0;
    }

    @SuppressWarnings("unchecked")
    private static List<String> historyOf(OverAllState state) {
        Object value = state.value(K_HISTORY).orElse(null);
        return value instanceof List ? (List<String>) value : List.of();
    }

    /** 只保留最近若干条历史 —— 带太多既费 token，也容易把模型带偏。 */
    private static List<String> recent(List<String> history) {
        return history.size() <= MAX_HISTORY_ITEMS
                ? history
                : history.subList(history.size() - MAX_HISTORY_ITEMS, history.size());
    }

    // ============================================================
    // 文本处理
    // ============================================================

    /**
     * 清洗模型返回的 SQL。
     *
     * 【为什么必须做这一步】
     *   即使提示词里写了"不要 markdown 代码块"，模型仍有相当概率输出：
     *       ```sql
     *       SELECT ...
     *       ```
     *   这是模型的强先验，靠提示词压不干净。所以**在解析侧兜住**，
     *   而不是反复加粗提示词 —— 又是"在正确的层解决问题"。
     *   （和第 6 步修 ¥ 符号是同一个道理：模型自由输出，边界处适配。）
     */
    private static String cleanSql(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            if (firstNewline >= 0) {
                s = s.substring(firstNewline + 1);
            }
            int closingFence = s.lastIndexOf("```");
            if (closingFence >= 0) {
                s = s.substring(0, closingFence);
            }
        }
        return s.trim();
    }

    /**
     * 清洗模型返回的「改写后的问题」。
     *
     * 【和 cleanSql 长得像，为什么不合并】
     *   两者要剥掉的东西并不相同：SQL 主要防 markdown 代码块，
     *   问题主要防"改写结果："这类前缀和首尾引号。
     *   合并成一个通吃的解析器，两边都要塞一堆不属于自己的分支判断 ——
     *   为了消除几行相似代码而引入一个更复杂的东西，是负收益。
     */
    private static String cleanRewrite(String raw) {
        String s = raw == null ? "" : raw.trim();

        // 模型偶尔仍会包 markdown 代码块
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            if (firstNewline >= 0) {
                s = s.substring(firstNewline + 1);
            }
            int closingFence = s.lastIndexOf("```");
            if (closingFence >= 0) {
                s = s.substring(0, closingFence);
            }
            s = s.trim();
        }

        // 去掉前缀（提示词已说明不要加，但它有时还是会加）
        for (String prefix : new String[]{"改写结果：", "改写后：", "改写：", "结果："}) {
            if (s.startsWith(prefix)) {
                s = s.substring(prefix.length()).trim();
            }
        }

        // 去掉首尾包裹的引号
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            s = s.substring(1, s.length() - 1).trim();
        }

        // 只取第一行 —— 防止模型在下面又补一句"（说明：……）"
        int newline = s.indexOf('\n');
        if (newline > 0) {
            s = s.substring(0, newline).trim();
        }
        return s;
    }

    /**
     * 把「上个月 / 本月 / 最近 N 天」这类相对时间，**在程序侧算成具体区间**再交给模型。
     *
     * ============================================================
     * 为什么不让模型自己用 CURDATE()/DATE_SUB() 算
     * ============================================================
     *   这是我在这一个点上反复栽了三次跟头才想明白的：
     *
     *   第 1 版：只写"要用数据库函数计算"，没给映射
     *     → "上个月支出多少" 时对时错（有时写成当前月，查出 0）
     *   第 2 版：把映射写清楚，还加了 ★ 警告"绝不能写成 DATE_FORMAT(CURDATE(),...)"
     *     → "上个月" 稳了，但「这个月支出多少」被答成了上个月 —— 矫枉过正
     *
     *   根因不在措辞，在**分工错了**：日期区间是可以被精确计算的东西，
     *   让概率模型去"推理"它，本来就是把它放在了最容易出错的位置。
     *   这和第 6 步"把当前日期注入系统提示词"是同一个道理 ——
     *   **不要让模型做它能被算清楚的事**。
     *
     *   所以改成：程序算好区间 → 模型只负责"选对哪一行"。
     *   模型擅长的正是"上个月"要对应哪一行这种语义映射，而不是日历算术。
     *
     *   代价：SQL 里的日期写成了具体值（'2026-09-01'）而不是函数调用，
     *   看起来不够"优雅"。但正确性优先 —— 而且这些值是由程序算出来的，
     *   不存在"跑一个月就失效"的问题。
     */
    private static LocalDate today() {
        return LocalDate.now();
    }

    /** 上个月的起点（含） */
    private static LocalDate lastMonthStart() {
        return today().minusMonths(1).withDayOfMonth(1);
    }

    /** 本月的起点（含）—— 同时也是上个月的终点（不含） */
    private static LocalDate thisMonthStart() {
        return today().withDayOfMonth(1);
    }

    private static String timeReference() {
        LocalDate today = today();
        LocalDate lastMonthStart = lastMonthStart();
        LocalDate thisMonthStart = thisMonthStart();
        LocalDate nextMonthStart = thisMonthStart.plusMonths(1);

        return """
                - 今天：%s
                - 「上个月」  → create_time >= '%s' AND create_time < '%s'
                - 「本月 / 这个月 / 当月」 → create_time >= '%s' AND create_time < '%s'
                - 「最近 N 天」→ create_time >= 今天往前推 N 天（例：最近 90 天 → >= '%s'）
                - 「今年」    → create_time >= '%s'
                """.formatted(
                today,
                lastMonthStart, thisMonthStart,
                thisMonthStart, nextMonthStart,
                today.minusDays(90),
                today.withDayOfYear(1));
    }

    private static String firstLine(String text) {
        int nl = text.indexOf('\n');
        return nl < 0 ? text : text.substring(0, nl);
    }

    private static String oneLine(String text) {
        if (text == null) {
            return "";
        }
        String s = text.replaceAll("\\s+", " ").trim();
        return s.length() > 150 ? s.substring(0, 150) + "…" : s;
    }

    private void log(String node, String message) {
        if (verbose) {
            System.out.printf("[节点 %-10s] %s%n", node, message);
        }
    }

    /** 供外部（如对比程序）使用：把轨迹列表转成可读字符串 */
    public static String traceOf(Map<String, Object> finalState) {
        Object trace = finalState.get(K_TRACE);
        return trace instanceof List ? String.join(" → ", ((List<?>) trace).stream()
                .map(String::valueOf).toList()) : "";
    }

    public static String answerOf(Map<String, Object> finalState) {
        Object answer = finalState.get(K_ANSWER);
        return answer == null ? "" : answer.toString();
    }

    /**
     * 把一轮问答压成一条历史摘要，供下一轮改写使用。
     *
     * 【为什么放在这里，而不是各自实现】
     *   CLI（Step9Main）和 Web（AgentServer）都要往 history 里追加东西。
     *   如果两处各写一份格式，早晚会漂移成
     *     "用户：xxx｜回答：yyy" 和 "问: xxx / 答: yyy"
     *   而改写提示词是按某一种格式调过的 —— 格式一变，指代消解的质量就跟着变。
     *   统一在这里，两头都用同一个。
     *
     * 【为什么要截断回答】
     *   回答可能很长（尤其是带明细的），原样塞进历史会让下一轮的 prompt 迅速膨胀。
     *   指代消解需要的是"上一轮聊的是什么话题"，不是完整的答案原文，
     *   所以保留 120 字符足够。
     */
    public static String historyLine(String question, Map<String, Object> state) {
        String answer = answerOf(state).replaceAll("\\s+", " ").trim();
        if (answer.length() > 120) {
            answer = answer.substring(0, 120) + "…";
        }
        return "用户：" + question + "｜回答：" + answer;
    }

    /** 供测试用：暴露最大尝试次数 */
    public static int maxAttempts() {
        return MAX_ATTEMPTS;
    }
}
