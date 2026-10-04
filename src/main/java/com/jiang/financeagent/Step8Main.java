package com.jiang.financeagent;

import com.jiang.financeagent.agent.LlmClient;
import com.jiang.financeagent.eval.EvalCase;
import com.jiang.financeagent.eval.EvalSet;
import com.jiang.financeagent.eval.Evaluator;
import com.jiang.financeagent.util.Text;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * 第 8 步入口：跑评测集，把「准不准」变成一个数字。
 *
 * 用法：
 *   bash dev.sh run Step8Main                跑全部 20 条
 *   bash dev.sh run Step8Main L2             只跑 L2（时间筛选）那 4 条
 *   bash dev.sh run Step8Main D06,D14        只跑指定编号，改完一条立刻重测
 *
 * 【为什么要支持只跑一部分】
 *   全量一轮要几分钟、要花 token。调参时通常只想验证某几条，
 *   支持过滤能让"改一点 → 立刻验证"这个循环转起来。
 *   评测集如果只能整跑，就会变成"最后跑一次给别人看"的摆设。
 *
 * 输出：
 *   控制台（GBK 安全，不用 emoji）+ target/eval-report.md（UTF-8，可贴进 README）
 */
public class Step8Main {

    public static void main(String[] args) throws Exception {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.out.println("未读到 DEEPSEEK_API_KEY，请确认环境变量已配置并重启终端");
            return;
        }

        List<EvalCase> cases = filter(EvalSet.all(), args);

        System.out.println("================ 评测开始 ================");
        System.out.println("用例数：" + cases.size() + "（数据 " + cases.stream().filter(EvalCase::isDataCase).count()
                + " / 安全 " + cases.stream().filter(c -> !c.isDataCase()).count() + "）");
        System.out.println("说明：判分方式 = 黄金 SQL 结果比对（比数字对不对，不比 SQL 写法）");
        System.out.println();

        Evaluator evaluator = new Evaluator();
        long start = System.currentTimeMillis();
        Evaluator.Report report = evaluator.run(new LlmClient(apiKey), cases, Step8Main::printCase);

        printSummary(report);

        // 报告刻意不写进 target/ —— 那是编译产物目录，mvn clean 会整个删掉，
        // 评测报告是"成果"不是"中间产物"，必须能活过下一次编译。
        Path md = Evaluator.writeMarkdown(report, Path.of("reports", "eval-report.md"));
        System.out.println("详细报告已写入：" + md.toAbsolutePath());
        System.out.println("（该文件是 UTF-8，可以直接贴进 README 或作为简历附件）");
    }

    /** 用例过滤：支持 L2 这样的级别，或 D06,D14 这样的编号列表 */
    private static List<EvalCase> filter(List<EvalCase> all, String[] args) {
        if (args.length == 0) {
            return all;
        }
        String selector = args[0].trim();
        if (selector.toUpperCase(Locale.ROOT).startsWith("L")) {
            return all.stream()
                    .filter(c -> c.level().equalsIgnoreCase(selector))
                    .toList();
        }
        List<String> ids = List.of(selector.split(","));
        return all.stream().filter(c -> ids.contains(c.id())).toList();
    }

    private static void printCase(EvalCase evalCase, Evaluator.CaseResult result, int index, int total) {
        String mark;
        String detail;
        if (!evalCase.isDataCase()) {
            mark = result.passed() ? "√ 通过  " : "× 未通过";
            detail = result.failureReason() == null ? "安全防线未被突破" : result.failureReason();
        } else if (result.fullyCorrect()) {
            mark = "√ 完全对";
            detail = result.expected().size() + " 个数值全部命中";
        } else if (result.basicallyCorrect()) {
            mark = "~ 基本对";
            detail = String.format("命中 %.0f%%，缺 %s", result.hitRatio() * 100, result.missing());
        } else {
            mark = "× 错误  ";
            detail = result.failureReason() == null ? "" : result.failureReason();
        }

        String q = Text.padRight(evalCase.question(), 34);
        System.out.printf("[%2d/%2d] %s %-4s %s  %s  工具%d次 %dms %dtoken%n",
                index, total, evalCase.id(), evalCase.level(), mark, q,
                result.toolCalls(), result.elapsedMs(), result.tokens());
        System.out.println("        └─ " + Text.truncate(detail, 150));
    }

    private static void printSummary(Evaluator.Report report) {
        long dataTotal = report.results().stream().filter(r -> r.evalCase().isDataCase()).count();
        long safeTotal = report.results().stream().filter(r -> !r.evalCase().isDataCase()).count();

        System.out.println();
        System.out.println("================ 汇总 ================");
        System.out.printf("SQL 执行成功率        : %d / %d%n", report.executedCount(), dataTotal);
        System.out.printf("结果完全正确率        : %d / %d  (%.1f%%)%n",
                report.fullyCorrectCount(), dataTotal, pct(report.fullyCorrectCount(), dataTotal));
        System.out.printf("结果基本正确率(>=80%%) : %d / %d  (%.1f%%)%n",
                report.basicallyCorrectCount(), dataTotal, pct(report.basicallyCorrectCount(), dataTotal));
        if (safeTotal > 0) {
            System.out.printf("安全用例通过率        : %d / %d  (%.1f%%)%n",
                    report.safePassCount(), safeTotal, pct(report.safePassCount(), safeTotal));
        }
        System.out.printf("平均工具调用          : %.2f 次/题%n", report.avgToolCalls());
        System.out.printf("平均 token            : %.0f token/题（合计 %d）%n", report.avgTokens(), report.totalTokens());
        System.out.printf("平均耗时              : %.0f ms/题（总 %d s）%n", report.avgMs(), report.totalMs() / 1000);
        System.out.println();
        System.out.println("看数字的提醒：");
        System.out.println("  1. 「完全正确」比「基本正确」严格 —— 分组类问题要求每一个数字都对。");
        System.out.println("  2. 数值比对只测「数据对不对」，测不出「表达好不好」，后者需要人工抽检。");
        System.out.println("  3. 单轮 20 条有随机性（模型不完全确定），要判断改动是否有效，应多跑两轮看趋势。");
    }

    private static double pct(long part, long total) {
        return total == 0 ? 0 : part * 100.0 / total;
    }
}
