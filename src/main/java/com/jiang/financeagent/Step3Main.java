package com.jiang.financeagent;

import com.jiang.financeagent.tool.SqlGuard;
import com.jiang.financeagent.tool.SqlTool;
import com.jiang.financeagent.util.Text;

import java.util.List;
import java.util.Locale;

/**
 * 第 3 步的验证入口：SQL 安全防线实战。
 *
 *   第一幕：看「朴素黑名单」怎么被绕过（反面教材）
 *   第二幕：跑完整的攻防用例（正面防线）
 *   第三幕：真正执行两条 SQL，看返回的 JSON 长什么样
 */
public class Step3Main {

    /** 一条测试用例 */
    private record Case(String kind, String sql, boolean shouldAllow) {
    }

    /** 攻击用例集：deny 的每一条都对应一类真实威胁 */
    private static final List<Case> CASES = List.of(
            // ---------- 合法查询 ----------
            new Case("合法", "SELECT COUNT(*) FROM t_transaction", true),
            new Case("合法", "SELECT account_name, balance FROM t_account WHERE status = '正常'", true),
            new Case("合法", "SELECT c.category_name, SUM(t.amount) AS 合计 "
                    + "FROM t_transaction t JOIN t_category c ON t.category_id = c.category_id "
                    + "WHERE t.status = '支出' GROUP BY c.category_name", true),
            new Case("合法", "select * from t_account", true),

            // ---------- 写操作 ----------
            new Case("写操作", "DELETE FROM t_transaction", false),
            new Case("写操作", "DeLeTe FrOm t_transaction", false),
            new Case("写操作", "DROP TABLE t_transaction", false),
            new Case("写操作", "UPDATE t_account SET balance = 0", false),
            new Case("写操作", "TRUNCATE TABLE t_transaction", false),

            // ---------- 多语句注入 ----------
            new Case("多语句", "SELECT 1; TRUNCATE TABLE t_transaction", false),
            new Case("多语句", "SELECT * FROM t_transaction; DROP TABLE t_account", false),

            // ---------- 越权读表 ----------
            new Case("越权读", "SELECT * FROM user", false),
            new Case("越权读", "SELECT password FROM user", false),
            new Case("越权读", "SELECT * FROM t_transaction UNION SELECT * FROM user", false),
            new Case("越权读", "SELECT * FROM information_schema.tables", false),

            // ---------- 文件与 DoS ----------
            new Case("文件/DoS", "SELECT LOAD_FILE('/etc/passwd')", false),
            new Case("文件/DoS", "SELECT * FROM t_transaction INTO OUTFILE '/tmp/a.txt'", false),
            new Case("文件/DoS", "SELECT SLEEP(30)", false),

            // ---------- 边界：注释里的话不会被执行，应当放行 ----------
            new Case("边界", "SELECT * FROM t_account WHERE 1=1 -- ' AND status='支出", true)
    );

    public static void main(String[] args) {
        act1NaiveBlacklist();
        act2AttackSuite();
        act3RealExecution();
    }

    // ============================================================
    // 第一幕：朴素黑名单的四个致命漏洞
    // ============================================================
    private static void act1NaiveBlacklist() {
        System.out.println("=".repeat(72));
        System.out.println("第一幕：如果只按「不含 delete/drop/update」来判断，会发生什么？");
        System.out.println("=".repeat(72));
        System.out.println();

        String[] bypasses = {
                "SELECT 1; TRUNCATE TABLE t_transaction",
                "SELECT * FROM user",
                "SELECT * FROM t_transaction INTO OUTFILE '/tmp/a.txt'",
                "SELECT SLEEP(600)"
        };
        String[] why = {
                "黑名单里没有 truncate",
                "语法完全合法，只是读了不该读的表",
                "语法合法，但这是「写文件」",
                "语法合法，但会让数据库卡死 10 分钟"
        };

        System.out.println(Text.padRight("朴素检查结论", 14)
                + Text.padRight("SQL", 46) + "为什么是漏的");
        System.out.println("-".repeat(72));
        for (int i = 0; i < bypasses.length; i++) {
            String verdict = naiveAllow(bypasses[i]) ? "放行 ✗" : "拦截";
            System.out.println(Text.padRight(verdict, 14)
                    + Text.padRight(Text.truncate(bypasses[i], 44), 46) + why[i]);
        }

        System.out.println();
        System.out.println("结论：黑名单永远列不全 —— SQL 的危险能力是开放集合，你补一个它换一个。");
        System.out.println("      正确做法是白名单：只允许 SELECT、只能碰指定表、强制限行。");
        System.out.println();
    }

    /** 反面教材：朴素黑名单 */
    private static boolean naiveAllow(String sql) {
        String s = sql.toLowerCase(Locale.ROOT);
        return s.startsWith("select")
                && !s.contains("delete")
                && !s.contains("drop")
                && !s.contains("update");
    }

    // ============================================================
    // 第二幕：完整攻防用例
    // ============================================================
    private static void act2AttackSuite() {
        System.out.println("=".repeat(72));
        System.out.println("第二幕：SqlGuard 完整攻防用例（" + CASES.size() + " 条）");
        System.out.println("=".repeat(72));

        int pass = 0;
        int index = 1;
        for (Case c : CASES) {
            SqlGuard.Verdict verdict = SqlGuard.check(c.sql());
            boolean matched = verdict.allowed() == c.shouldAllow();
            if (matched) {
                pass++;
            }

            String expected = c.shouldAllow() ? "放行" : "拦截";
            String actual = verdict.allowed() ? "放行" : "拦截";
            String mark = matched ? "✔" : "✘";

            System.out.printf("%2d %s %s %s  %s%n",
                    index++,
                    mark,
                    Text.padRight("[" + c.kind() + "]", 12),
                    Text.padRight(Text.truncate(c.sql(), 42), 44),
                    "期望 " + expected + " / 实际 " + actual
                            + (verdict.allowed() ? "" : "  ← " + verdict.reason()));
        }

        System.out.println("-".repeat(72));
        System.out.println("结果：" + pass + " / " + CASES.size() + " 符合预期");
        System.out.println();
    }

    // ============================================================
    // 第三幕：真正执行，看返回结构
    // ============================================================
    private static void act3RealExecution() {
        System.out.println("=".repeat(72));
        System.out.println("第三幕：真实执行（结果就是回传给模型的工具返回值）");
        System.out.println("=".repeat(72));

        show("查询一：最近三个月每月支出",
                "SELECT DATE_FORMAT(create_time,'%Y-%m') AS 月份, SUM(amount) AS 支出合计 "
                        + "FROM t_transaction WHERE status='支出' AND create_time >= '2026-07-01' "
                        + "GROUP BY 月份 ORDER BY 月份");

        show("查询二：故意写错列名（看错误如何被包装成数据）",
                "SELECT createTime FROM t_transaction LIMIT 5");

        System.out.println("重点：第二条并没有抛异常让程序崩溃，而是把数据库的报错原样返回。");
        System.out.println("      模型收到 {" + "\"error\"" + ": \"...Unknown column 'createTime'...\"} 后，");
        System.out.println("      就能自己把 createTime 改成 create_time 重试 —— 这就是第 6 步「自动重试」的基础。");
    }

    private static void show(String title, String sql) {
        System.out.println();
        System.out.println("── " + title + " ──");
        System.out.println("输入 SQL : " + sql);
        String result = SqlTool.executeReadOnlySql(sql);
        System.out.println("返回     : " + result);
    }
}
