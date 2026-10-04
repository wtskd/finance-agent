package com.jiang.financeagent.eval;

import java.util.List;

/**
 * 一条评测用例。
 *
 * ============================================================
 * 一个必须先想清楚的问题：怎么判定「答对了」？
 * ============================================================
 *   三种判分方式的对比：
 *
 *   | 方式              | 做法                          | 问题                        |
 *   |-------------------|-------------------------------|-----------------------------|
 *   | A 自然语言比对     | 把回答和标准答案做文本相似度    | 不可靠：\"7,053.00元\" vs \"约七千\" |
 *   | B 让大模型当裁判   | 再调一次模型判断对错            | 引入第二个不确定性，还贵        |
 *   | C **SQL 结果比对** | 预先写好黄金 SQL，比"数字对不对" | ★ 采用                       |
 *
 *   选 C 的理由：问数系统的核心正确性就是**数字对不对**，而这是可判定的。
 *   表达得好不好属于质量维度，用人工抽检即可，不该混进自动指标。
 *
 *   于是每条用例长这样：
 *     question   → 问什么
 *     goldenSql  → 我们自己手写一条"标准答案 SQL"，直接执行得到预期结果
 *     mustContain → （安全类用例用）回答里必须出现的词
 *
 * ============================================================
 * 一个额外的好处：评测集顺带成了安全白名单的回归测试
 * ============================================================
 *   黄金 SQL 走的是和 Agent 完全相同的通道（SqlTool → SqlGuard）。
 *   所以如果哪天有人把白名单改窄了，评测集会在跑之前就直接报错 ——
 *   不用等线上出问题才发现。
 *
 * @param id          用例编号，如 D01 / S01
 * @param level       难度分级：L1 简单聚合 / L2 时间筛选 / L3 分组聚合 / L4 多表关联 / L5 复合计算 / L6 安全边界
 * @param question    给 Agent 的自然语言问题
 * @param goldenSql   标准答案 SQL（安全类用例为空）
 * @param mustContain 回答里必须出现的词（任一命中即可；安全类用例用）
 * @param mustNotContain 回答里不能出现的词
 * @param note        这条用例在考察什么
 */
public record EvalCase(String id,
                       String level,
                       String question,
                       String goldenSql,
                       List<String> mustContain,
                       List<String> mustNotContain,
                       String note) {

    /** 数据类用例：有黄金 SQL */
    public static EvalCase data(String id, String level, String question, String goldenSql, String note) {
        return new EvalCase(id, level, question, goldenSql, List.of(), List.of(), note);
    }

    /** 安全类用例：没有黄金 SQL，改判"回答里该不该出现某些话" */
    public static EvalCase safe(String id, String level, String question,
                                List<String> mustContain, List<String> mustNotContain, String note) {
        return new EvalCase(id, level, question, null, mustContain, mustNotContain, note);
    }

    public boolean isDataCase() {
        return goldenSql != null && !goldenSql.isBlank();
    }
}
