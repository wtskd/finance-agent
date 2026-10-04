package com.jiang.financeagent;

import com.jiang.financeagent.agent.FinanceAgent;
import com.jiang.financeagent.agent.LlmClient;
import com.jiang.financeagent.util.ConsoleInput;
import com.jiang.financeagent.util.Text;

/**
 * 第 4 步的入口：与财务问数 Agent 对话。
 *
 * 用法二选一：
 *   1) 问答模式：直接运行，然后在控制台输入问题（推荐，中文无编码问题）
 *   2) 单次模式：运行时带参数，例如  Step4Main "上个月支出多少"
 */
public class Step4Main {

    public static void main(String[] args) throws Exception {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.out.println("未读到 DEEPSEEK_API_KEY，请确认环境变量已配置并重启终端");
            return;
        }

        FinanceAgent agent = new FinanceAgent(new LlmClient(apiKey));

        // 单次模式：命令行带了问题，问完就退出
        if (args.length > 0) {
            askAndPrint(agent, String.join(" ", args));
            return;
        }

        // 问答模式
        ConsoleInput input = new ConsoleInput();
        System.out.println("=== 财务问数 Agent（输入 exit 退出）===");
        System.out.println("试试问：上个月支出多少？");

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

            askAndPrint(agent, question);
        }
    }

    /** 问一次并打印答案与耗时/token 统计 */
    private static void askAndPrint(FinanceAgent agent, String question) throws Exception {
        System.out.println("你> " + question);
        long start = System.currentTimeMillis();
        String answer = agent.ask(question);
        System.out.println();
        // forConsole：把 GBK 控制台显示不了的字符（半角 ¥、emoji）适配掉
        System.out.println("AI> " + Text.forConsole(answer));
        System.out.println("（用时 " + (System.currentTimeMillis() - start) + " ms，"
                + "累计 token " + agent.totalTokens() + "）");
    }
}
