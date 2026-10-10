package com.jiang.financeagent;

import com.jiang.financeagent.agent.FinanceAgent;
import com.jiang.financeagent.agent.LlmClient;
import com.jiang.financeagent.graph.FinanceGraph;
import com.jiang.financeagent.util.ConsoleInput;
import com.jiang.financeagent.util.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 第 9 步入口：跑「图编排版」的财务问数 Agent。
 *
 * 用法：
 *   bash dev.sh run Step9Main                   交互模式（★ 支持多轮对话）
 *   bash dev.sh run Step9Main "上个月支出多少"    单次模式
 *   bash dev.sh run Step9Main --compare "上个月支出多少"
 *                                               同一问题跑两版（手写循环 vs 图编排）并对比
 *
 * 【多轮对话是怎么做的】
 *   入口多了一个 `rewrite` 节点：它读「对话历史 + 当前问题」，
 *   把「那收入呢」这类省略/指代补全成能独立理解的问题，再交给后面的节点。
 *   设计要点见 FinanceGraph.rewrite() 的注释。
 *   交互模式下历史由本类维护（图不记忆），每轮结束后把「用户问 + 回答摘要」
 *   追加进 history。
 *
 * 【为什么要做 --compare】
 *   重构最容易骗自己的地方是"看起来都能跑"。
 *   把同一个问题分别喂给两版、把 token / 耗时 / 调用次数摆在一起，
 *   才能回答真正的问题：**这次重构到底换来了什么？**
 *
 *   我原本的预期是"只换来可观测性，成本和准确率不会变"——
 *   **实测把这个预期推翻了**（2026-10-04，同一问题"上个月支出多少"）：
 *
 *   |              | 手写 tool-calling | 图编排    |
 *   |--------------|------------------|----------|
 *   | 答案          | 7053.00 元        | 7053.00 元（一致）|
 *   | token        | 4391             | **1098**（↓75%）|
 *   | 耗时          | 3310 ms          | **1829 ms**（↓45%）|
 *
 *   原因在于 **tool-calling 的请求是无状态的**：每一轮都必须把
 *   「系统提示词 + 工具定义 + 之前所有轮次的消息」整段重发一遍，
 *   于是 token 随轮数累积。而图里每个节点只构造自己需要的最小 prompt，
 *   互不叠加 —— 这是重构带来的一个**意外但很有价值的收益**。
 *
 *   代价也要说清楚：图版回答更简短。因为 report 节点只能看到结果集，
 *   看不到中间过程；而手写版的历史里有完整上下文，能顺带说出笔数和日期范围。
 *   这不是不可调和的，把 report 节点的提示词写详细些就能补回来。
 */
public class Step9Main {

    public static void main(String[] args) throws Exception {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.out.println("未读到 DEEPSEEK_API_KEY，请确认环境变量已配置并重启终端");
            return;
        }

        boolean compare = args.length > 0 && "--compare".equals(args[0]);
        String[] rest = compare ? java.util.Arrays.copyOfRange(args, 1, args.length) : args;

        if (rest.length > 0) {
            String question = String.join(" ", rest);
            if (compare) {
                compare(apiKey, question);
            } else {
                askOnce(apiKey, question);
            }
            return;
        }

        // 交互模式（多轮对话）
        ConsoleInput input = new ConsoleInput();
        System.out.println("=== 财务问数 Agent（图编排版 · 多轮对话，输入 exit 退出）===");
        System.out.println("试试先问：上个月支出多少？   然后接着问：那收入呢？");

        // ★ 对话历史由**调用方**维护，图自己不留记忆 ——
        //   因为图每次都是新建的（状态隔离要求），它天然无状态。
        //   见 FinanceGraph.ask(String, List) 的注释。
        List<String> history = new ArrayList<>();
        LlmClient llm = new LlmClient(apiKey);   // 会话内复用，累计 token 才准

        while (true) {
            System.out.print("\n你> ");
            System.out.flush();
            String question = input.readLine();
            if (question == null) {
                break;
            }
            question = question.trim();
            if (question.isEmpty()) {
                continue;
            }
            if (question.equalsIgnoreCase("exit")) {
                break;
            }
            askRound(llm, question, history);
        }
    }

    /** 单次模式 = 没有历史的一轮，直接复用多轮逻辑。 */
    private static void askOnce(String apiKey, String question) throws Exception {
        System.out.println("你> " + question);
        askRound(new LlmClient(apiKey), question, new ArrayList<>());
    }

    /**
     * 跑一轮问答（支持多轮），打印答案与足迹，并把这一轮追加进 history。
     *
     * 【为什么每轮都 new 一个 FinanceGraph】
     *   状态隔离 —— 见 FinanceGraph.build() 的注释。开销 0.68 ms，可忽略。
     *   记忆不在图里，在传进来的 history 里。
     */
    private static void askRound(LlmClient llm, String question, List<String> history) throws Exception {
        int tokensBefore = llm.totalTokens();
        long start = System.currentTimeMillis();

        Map<String, Object> state = new FinanceGraph(llm, true).ask(question, history);

        long elapsed = System.currentTimeMillis() - start;

        // 追加历史时用的是**用户原话**，不是改写后的问题 ——
        // 下一轮改写需要看到"用户原本怎么说的"，否则会把改写的痕迹越叠越多。
        history.add(FinanceGraph.historyLine(question, state));

        System.out.println();
        System.out.println("AI> " + Text.forConsole(FinanceGraph.answerOf(state)));
        System.out.println();
        printFootprint(state, llm, tokensBefore, elapsed);
    }

    // 历史摘要的格式统一由 FinanceGraph.historyLine 提供，避免 CLI 与 Web 两处写法漂移。

    private static void printFootprint(Map<String, Object> state, LlmClient llm,
                                       int tokensBefore, long elapsedMs) {
        System.out.println("── 本次执行足迹 ──────────────────────────");
        System.out.println("走过节点 : " + FinanceGraph.traceOf(state));

        Object rewritten = state.get(FinanceGraph.K_REWRITTEN);
        if (rewritten != null) {
            System.out.println("问题改写 : " + Text.forConsole(String.valueOf(rewritten)));
        }

        System.out.println("需求核对 : " + state.getOrDefault(FinanceGraph.K_VERIFY_NOTE, "（未经过核对节点）"));
        System.out.println("SQL 尝试 : " + state.getOrDefault(FinanceGraph.K_ATTEMPTS, 0) + " 次");
        System.out.println("最终 SQL : " + state.getOrDefault(FinanceGraph.K_SQL, ""));
        System.out.println("本轮 token : " + (llm.totalTokens() - tokensBefore)
                + "（累计 " + llm.totalTokens() + "）");
        System.out.println("耗时     : " + elapsedMs + " ms");
    }

    /**
     * 两版对比。
     *
     * 【对比时要盯住什么】
     *   不是"谁更准"—— 两版用的是同一个模型、同一套工具，准确率本就该一样。
     *   要看的是：
     *     - 模型调用次数（决定成本和延迟）
     *     - 流程可见性（手写版只能靠日志猜，图版有明确轨迹）
     *     - 干预能力（图版可以在任意节点插入校验/审计）
     */
    private static void compare(String apiKey, String question) throws Exception {
        System.out.println("═══════════ 对比：同一问题，两种编排 ═══════════");
        System.out.println("问题：" + question);
        System.out.println();

        // ---- 手写 tool-calling 循环 ----
        System.out.println("────────── A. 手写 tool-calling 循环（第 4 步）──────────");
        LlmClient llm1 = new LlmClient(apiKey);
        FinanceAgent agent = new FinanceAgent(llm1);
        long t1 = System.currentTimeMillis();
        String answer1 = agent.ask(question);
        long ms1 = System.currentTimeMillis() - t1;
        System.out.println();
        System.out.println("AI> " + Text.forConsole(answer1));
        System.out.println();
        System.out.println("工具调用次数 : " + agent.toolCalls().size()
                + "（" + agent.toolCalls().stream().map(FinanceAgent.ToolCall::tool).toList() + "）");
        System.out.println("token        : " + agent.totalTokens() + "（客户端侧 " + llm1.totalTokens() + "）");
        System.out.println("耗时         : " + ms1 + " ms");
        System.out.println();

        // ---- 图编排 ----
        System.out.println("────────── B. 图编排（第 9 步）──────────");
        LlmClient llm2 = new LlmClient(apiKey);
        FinanceGraph graph = new FinanceGraph(llm2, true);
        long t2 = System.currentTimeMillis();
        Map<String, Object> state = graph.ask(question);
        long ms2 = System.currentTimeMillis() - t2;
        System.out.println();
        System.out.println("AI> " + Text.forConsole(FinanceGraph.answerOf(state)));
        System.out.println();
        System.out.println("走过节点     : " + FinanceGraph.traceOf(state));
        System.out.println("token        : " + llm2.totalTokens());
        System.out.println("耗时         : " + ms2 + " ms");
        System.out.println();

        System.out.println("────────── 结论 ──────────");
        System.out.println("答案一致（同模型、同工具、同 Schema 召回），但成本差很多：");
        System.out.println("  手写版每轮都重发整段历史 + 工具定义，token 随轮数累积；");
        System.out.println("  图版每个节点各发各的最小 prompt，不叠加。");
        System.out.println("图上能直接对比上面两行的 token 与耗时数字。");
        System.out.println();
        System.out.println("另外两点图版独有的能力：");
        System.out.println("  1. 轨迹可见：走过哪些节点一目了然，手写版只能翻日志猜。");
        System.out.println("  2. 可插拔：想在「执行 SQL 前」加人工确认或审计，挂一个节点即可。");
    }
}
