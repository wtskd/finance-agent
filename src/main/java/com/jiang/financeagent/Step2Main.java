package com.jiang.financeagent;

import com.jiang.financeagent.tool.SchemaTool;

/**
 * 第 2 步的验证入口：把「给模型看的表结构」打印出来。
 *
 * 这一步还没有接大模型 —— 先把「模型能看到什么」这个前提做对，
 * 再接模型才有意义。调试 Agent 的正确顺序是：
 *   先把每个工具单独跑通 → 再组装成 Agent
 * 反过来（直接组装）出问题时，你分不清是工具错了还是模型错了。
 */
public class Step2Main {

    public static void main(String[] args) throws Exception {
        long start = System.currentTimeMillis();
        String schema = SchemaTool.describeSchema();
        long cost = System.currentTimeMillis() - start;

        System.out.println(schema);

        System.out.println("================ 统计 ================");
        System.out.println("字符数    : " + schema.length());
        System.out.println("耗时      : " + cost + " ms");
        System.out.println("粗估 token: 约 " + estimateTokens(schema) + "（中文约 1 字 1 token，英文约 4 字符 1 token）");
        System.out.println();
        System.out.println("提示：这段文本后续会拼进 prompt 发给 DeepSeek。");
        System.out.println("      token 数直接等于成本 —— 表越多，这个数字越要控制。");
    }

    private static int estimateTokens(String text) {
        int chinese = 0;
        int other = 0;
        for (char c : text.toCharArray()) {
            if (c > 0x2E80) {
                chinese++;
            } else {
                other++;
            }
        }
        return chinese + other / 4;
    }
}
