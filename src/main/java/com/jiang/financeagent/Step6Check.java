package com.jiang.financeagent;

import com.jiang.financeagent.tool.SqlTool;

/**
 * 第 6 步验证：错误整形效果（纯本地，不调用大模型，零 token 成本）。
 *
 * 目的：看清楚「改造前 vs 改造后」工具返回的错误长什么样。
 * 模型能不能自愈，取决于它看到的信息够不够用来改。
 */
public class Step6Check {

    public static void main(String[] args) {
        show("① 列名写错（驼峰 vs 下划线）", "SELECT createTime FROM t_transaction LIMIT 3");
        show("② 表名写错", "SELECT * FROM t_transactionn");
        show("③ 语法错误", "SELECT COUNT( FROM t_transaction");
        show("④ 写操作 → 被安全策略拒绝", "DELETE FROM t_transaction");
        show("⑤ 越权读表 → 被白名单拒绝", "SELECT password FROM user");
        show("⑥ 正常查询（对照组）", "SELECT COUNT(*) AS 笔数 FROM t_transaction");
    }

    private static void show(String title, String sql) {
        System.out.println("── " + title + " ──");
        System.out.println("SQL  : " + sql);
        System.out.println("返回 : " + SqlTool.executeReadOnlySql(sql));
        System.out.println();
    }
}
