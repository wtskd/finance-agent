package com.jiang.financeagent;

import com.jiang.financeagent.eval.EvalCase;
import com.jiang.financeagent.eval.EvalSet;
import com.jiang.financeagent.metric.MetricSet;
import com.jiang.financeagent.util.Text;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 第 13 步入口：语义层（业务指标）匹配验证 —— 纯本地，零 token。
 *
 * ============================================================
 * 为什么语义层需要自己的验证程序
 * ============================================================
 *   语义层的核心是一个**规则匹配器**（问题里出现别名 → 命中该指标）。
 *   规则最怕的不是"不生效"，而是**生效错了**：
 *     - 该命中的没命中（漏）→ 退回原来的提示词规则，等于白做
 *     - 不该命中的命中了（误）→ 把错误的口径强塞给模型，比不做还糟
 *
 *   这两类问题跑端到端评测是看不出来的 —— 评测只会告诉你"这条错了"，
 *   但不会告诉你"是因为口径注入错了还是因为模型发挥失常"。
 *   所以我给匹配器单独写一个验证入口，把每次匹配的依据都打出来。
 *
 * ============================================================
 * 验证分两部分
 * ============================================================
 *   第一幕  评测集 26 条 —— 逐条与**人工标注的期望**对照。
 *           这是回归：语义层不能把已有用例的口径判断搞错。
 *   第二幕  同义说法补测 —— 这些是旧提示词规则认不出来的说法。
 *           这是增益：语义层真正"多覆盖"到了什么。
 *
 * 用法：
 *   bash dev.sh run Step13Main                跑全部验证
 *   bash dev.sh run Step13Main "上个月支出多少"  只看某一条的匹配与最终注入内容
 *   bash dev.sh run Step13Main --ab           对比"开/关语义层"的注入差异
 */
public final class Step13Main {

    /**
     * 人工标注的期望命中（用例编号 → 期望指标名，逗号分隔；空串表示期望不注入）。
     *
     * 这份期望表是**手写**的，不是从程序输出反推的 —— 否则就变成
     * "程序输出什么就认可什么"，验证毫无意义。
     * 标注时只依据一件事：**这句话问的到底是什么口径**。
     */
    private static final Map<String, String> EXPECTED = new LinkedHashMap<>();

    static {
        // ---- 数据用例 ----
        EXPECTED.put("D01", "笔数");                 // 一 共记了多少笔交易
        EXPECTED.put("D02", "收入");
        EXPECTED.put("D03", "支出");
        EXPECTED.put("D04", "");                     // 问账户个数，不是金额口径
        EXPECTED.put("D05", "");                     // 问余额合计，属 t_account，不在本指标集
        EXPECTED.put("D06", "支出");
        EXPECTED.put("D07", "收入");
        EXPECTED.put("D08", "支出");                 // 「花了多少钱」
        EXPECTED.put("D09", "笔数");
        EXPECTED.put("D10", "支出");
        EXPECTED.put("D11", "笔数");
        EXPECTED.put("D12", "支出,收入,笔数");        // 分组对比：两个方向 + 计数
        EXPECTED.put("D13", "支出");
        EXPECTED.put("D14", "支出");
        EXPECTED.put("D15", "支出");
        EXPECTED.put("D16", "支出");
        EXPECTED.put("D17", "净结余");                // 命中三个别名，被净结余吸收
        EXPECTED.put("D18", "净结余");
        // ---- 安全用例：都不该命中 ----
        EXPECTED.put("S01", "");
        EXPECTED.put("S02", "");
        // ---- 挑战用例 ----
        EXPECTED.put("E01", "支出");
        EXPECTED.put("E02", "支出");
        EXPECTED.put("E03", "支出");
        EXPECTED.put("E04", "");                     // 余额口径，不在指标集
        EXPECTED.put("E05", "");                     // 「几个」→ 不适用（问个数，不是总额）
        EXPECTED.put("E06", "");                     // 「最高」→ 不适用（问极值，不是总额）
    }

    /** 第二幕：旧提示词规则覆盖不到的同义说法 */
    private static final String[][] ALIAS_CASES = {
            {"我上个月花了多少钱？", "支出"},
            {"这个月我赚了多少？", "收入"},
            {"我的结余是多少？", "净结余"},
            {"一共记了几笔？", "笔数"},
            {"上个月有多少条交易记录？", "笔数"},
            {"最近 30 天消费了多少？", "支出"},
            {"上个月的总开销是多少？", "支出"},
            {"我进账了多少？", "收入"},
            {"收入减去支出之后是多少？", "净结余"},
    };

    public static void main(String[] args) {
        MetricSet metrics = MetricSet.load();

        if (args.length > 0 && !args[0].startsWith("--")) {
            showOne(metrics, String.join(" ", args));
            return;
        }
        if (args.length > 0 && args[0].equals("--ab")) {
            showAb(metrics);
            return;
        }

        printLoad(metrics);
        printCatalogue(metrics);
        int okEval = verifyEvalSet(metrics);
        int okAlias = verifyAliases(metrics);
        printSample(metrics);
        printSummary(metrics, okEval, okAlias);
    }

    // ---------------- 第一幕：加载情况 ----------------

    private static void printLoad(MetricSet metrics) {
        System.out.println("=== 1. 配置加载 ===");
        System.out.println("  " + metrics.loadNote());
        if (metrics.isEmpty()) {
            System.out.println();
            System.out.println("  ！指标集为空 —— 后续验证没有意义。");
            System.out.println("    请确认工作目录是项目根（dev.sh 会自动切过去），");
            System.out.println("    或检查 config/metrics.json 是否存在、JSON 语法是否正确。");
            System.out.println();
        }
    }

    // ---------------- 指标清单 ----------------

    private static void printCatalogue(MetricSet metrics) {
        System.out.println();
        System.out.println("=== 2. 指标定义 ===");
        System.out.println("  " + Text.padRight("名字", 10) + Text.padRight("聚合表达式", 62)
                + Text.padRight("附加筛选", 22) + "别名数");
        System.out.println("  " + "─".repeat(108));
        for (MetricSet.Metric m : metrics.all()) {
            System.out.println("  "
                    + Text.padRight(m.name(), 10)
                    + Text.padRight(Text.truncate(m.expression(), 58), 62)
                    + Text.padRight(m.where().isEmpty() ? "(无)" : m.where(), 22)
                    + m.aliases().size());
        }
        System.out.println();
        System.out.println("  不适用词（问题含这些词则不注入任何指标）：");
        System.out.println("    " + String.join(" / ", metrics.skipWhen()));
    }

    // ---------------- 第一幕：评测集回归 ----------------

    private static int verifyEvalSet(MetricSet metrics) {
        System.out.println();
        System.out.println("=== 3. 评测集匹配回归（26 条，与人工标注的期望对照）===");
        System.out.println("  " + Text.padRight("编号", 6) + Text.padRight("级别", 6)
                + Text.padRight("实际命中", 30) + Text.padRight("判定", 6) + "问题");
        System.out.println("  " + "─".repeat(108));

        int ok = 0;
        for (EvalCase c : EvalSet.all()) {
            String actual = hitNames(metrics, c.question());
            String expected = EXPECTED.getOrDefault(c.id(), "?");

            String mark;
            if (expected.equals("?")) {
                mark = "? 未标注";
            } else if (expected.equals(actual)) {
                mark = "√";
                ok++;
            } else {
                mark = "× 期望【" + expected + "】";
            }

            System.out.println("  " + Text.padRight(c.id(), 6) + Text.padRight(c.level(), 6)
                    + Text.padRight(displayHit(metrics, c.question()), 30)
                    + Text.padRight(mark, 6) + c.question());
        }
        System.out.println("  " + "─".repeat(108));
        System.out.println("  匹配正确：" + ok + " / " + EvalSet.all().size());
        return ok;
    }

    // ---------------- 第二幕：同义说法增益 ----------------

    private static int verifyAliases(MetricSet metrics) {
        System.out.println();
        System.out.println("=== 4. 同义说法补测（旧提示词规则认不出的说法）===");
        System.out.println("  这些说法在原提示词里**没有**对应条目，只能靠模型自行理解；");
        System.out.println("  语义层把它们变成确定性命中。");
        System.out.println();
        System.out.println("  " + Text.padRight("问题", 34) + Text.padRight("命中", 14)
                + Text.padRight("判定", 6) + "期望");
        System.out.println("  " + "─".repeat(96));

        int ok = 0;
        for (String[] pair : ALIAS_CASES) {
            String question = pair[0];
            String expected = pair[1];
            String actual = hitNames(metrics, question);
            boolean pass = expected.equals(actual);
            if (pass) {
                ok++;
            }
            System.out.println("  " + Text.padRight(question, 34)
                    + Text.padRight(displayHit(metrics, question), 16)
                    + Text.padRight(pass ? "√" : "×", 6) + expected);
        }
        System.out.println("  " + "─".repeat(96));
        System.out.println("  命中的：" + ok + " / " + ALIAS_CASES.length);
        return ok;
    }

    // ---------------- 注入内容预览 ----------------

    private static void printSample(MetricSet metrics) {
        System.out.println();
        System.out.println("=== 5. 实际注入提示词的片段（样例）===");
        for (String question : List.of("上个月支出多少？", "整理一下我的结余", "今年 3 月花了多少钱？")) {
            System.out.println();
            System.out.println("  问题：" + question);
            System.out.println("  " + "┈".repeat(80));
            for (String line : metrics.renderForPrompt(question).split("\n", -1)) {
                System.out.println("  " + line);
            }
        }
    }

    // ---------------- 单条诊断 ----------------

    private static void showOne(MetricSet metrics, String question) {
        System.out.println("=== 单条匹配诊断 ===");
        System.out.println("  问题：" + question);
        System.out.println("  配置：" + metrics.loadNote());
        System.out.println();

        List<String> raw = metrics.all().stream()
                .filter(m -> m.aliases().stream().anyMatch(question::contains))
                .map(MetricSet.Metric::name)
                .toList();

        System.out.println("  词面命中（未做过滤）：" + (raw.isEmpty() ? "(无)" : String.join(",", raw)));
        String skip = metrics.skipReason(question);
        if (skip != null) {
            System.out.println("  被不适用词拦下：「" + skip + "」→ 问的不是总额，不注入指标");
        }
        System.out.println("  最终命中：" + displayHit(metrics, question));
        System.out.println();
        System.out.println("  ── 最终注入内容 ──");
        for (String line : metrics.renderForPrompt(question).split("\n", -1)) {
            System.out.println("  " + line);
        }
    }

    // ---------------- 开/关对比 ----------------

    private static void showAb(MetricSet metrics) {
        System.out.println("=== 语义层开 / 关：同一批问题的注入差异 ===");
        System.out.println("  （关 = 等价于加语义层之前的提示词规则，走内置兜底说明）");
        System.out.println();
        for (EvalCase c : EvalSet.all()) {
            if (c.level().equals("L6")) {
                continue;                    // 安全用例跳过，两者都一样
            }
            String on = hitNames(metrics, c.question());
            System.out.println("  " + Text.padRight(c.id(), 6) + Text.padRight(on.isEmpty() ? "(不注入)" : on, 24)
                    + c.question());
        }
        System.out.println();
        System.out.println("  注：关闭语义层时靠模型自行判断口径，靠提示词里那段自然语言规则；");
        System.out.println("      开启后命中项直接给出已确定表达式，模型只负责填进 SQL。");
    }

    // ---------------- 汇总 ----------------

    private static void printSummary(MetricSet metrics, int okEval, int okAlias) {
        System.out.println();
        System.out.println("================ 汇总 ================");
        System.out.println("  指标数            : " + metrics.all().size());
        System.out.println("  不适用词数        : " + metrics.skipWhen().size());
        System.out.println("  评测集匹配正确    : " + okEval + " / " + EvalSet.all().size());
        System.out.println("  同义说法命中      : " + okAlias + " / " + ALIAS_CASES.length);
        System.out.println("  token 消耗        : 0（纯本地规则，不调用模型）");
        System.out.println();
        System.out.println("  下一步：跑端到端评测，确认准确率不退步（并做 A/B 对比）");
        System.out.println("    bash dev.sh run Step8Main --graph");
        System.out.println("    JAVA_TOOL_OPTIONS=\"-Dfinance.metrics=off\" bash dev.sh run Step8Main --graph");
    }

    // ---------------- 工具 ----------------

    /** 命中指标名（逗号分隔）。不注入时返回空串 —— 用于和期望值比较 */
    private static String hitNames(MetricSet metrics, String question) {
        return metrics.match(question).stream()
                .map(MetricSet.Metric::name)
                .reduce((a, b) -> a + "," + b)
                .orElse("");
    }

    /**
     * 表格展示用。
     * 空串有三种含义完全不同的情况，必须区分开 —— 它们对应三种不同的排查方向：
     *   - 没有任何别名命中        → "(不注入)"      说明词表没覆盖，接口本身没问题
     *   - 命中了别名但被规则拦下  → "(不适用：几个)" 说明是 skipWhen 生效，属预期行为
     * 混在一起就分不清"该加别名"还是"规则太严"。
     *
     * 注意顺序：先看有没有词面命中，再看 skipWhen。
     * 否则「我总共开了几个账户」这种**本来就没命中任何别名**的问题，
     * 也会被报成"不适用：几个"，读起来像是规则误拦 —— 其实是它压根没进过指标匹配。
     */
    private static String displayHit(MetricSet metrics, String question) {
        String names = hitNames(metrics, question);
        if (!names.isEmpty()) {
            return names;
        }
        String skip = metrics.skipReason(question);
        if (skip != null && hasAliasHit(metrics, question)) {
            return "(不适用：" + skip + ")";
        }
        return "(不注入)";
    }

    /** 问题里是否出现了任一指标的别名（不看 skipWhen，纯词面对照） */
    private static boolean hasAliasHit(MetricSet metrics, String question) {
        for (MetricSet.Metric metric : metrics.all()) {
            for (String alias : metric.aliases()) {
                if (question.contains(alias)) {
                    return true;
                }
            }
        }
        return false;
    }
}
