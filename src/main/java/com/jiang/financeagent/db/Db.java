package com.jiang.financeagent.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * 数据库连接配置。
 *
 * 【为什么用只读账号 ai_readonly，而不是 root】
 *   Agent 会把模型生成的 SQL 直接交给数据库执行。用 root 相当于把家门钥匙交给一个
 *   偶尔会犯错的助手；用只读账号，即使模型真的生成了 DELETE / DROP，数据库也会拒绝。
 *   这是「数据库权限」这一层的防线，代码层的 SQL 校验是另一层（第 3 步做）。
 *
 * 【关于账号密码：为什么不写在代码里】
 *   这个项目会传到 GitHub。硬编码的真实密码一旦提交，就等同于公开事故。
 *   所以密码只通过环境变量 FINANCE_DB_PASSWORD 提供，代码里不留任何默认值：
 *     · 命令行：bash dev.sh run ...   （dev.sh 会自动从 dev.local.sh 读取并注入）
 *     · IDEA  ：Run Configuration → Environment variables 里配置
 *   仓库里只提供 dev.local.sh.example 模板，真实值放在本地、不进版本库。
 */
public final class Db {

    /** 库名。模型生成 SQL 时也需要知道库名，所以设为 public */
    public static final String DATABASE = "personal_finance";

    private static final String HOST =
            System.getenv().getOrDefault("FINANCE_DB_HOST", "localhost:3306");

    public static final String USER =
            System.getenv().getOrDefault("FINANCE_DB_USER", "ai_readonly");

    private static final String PASSWORD = System.getenv("FINANCE_DB_PASSWORD");

    /**
     * useSSL=false                    本地开发不需要 SSL
     * allowPublicKeyRetrieval=true    MySQL 8 默认 caching_sha2_password 认证需要
     * serverTimezone=Asia/Shanghai    否则时间字段可能差 8 小时
     * characterEncoding=UTF-8          中文（收入/支出）不能乱码
     */
    private static final String URL = "jdbc:mysql://" + HOST + "/" + DATABASE
            + "?useSSL=false"
            + "&allowPublicKeyRetrieval=true"
            + "&serverTimezone=Asia/Shanghai"
            + "&characterEncoding=UTF-8";

    private Db() {
    }

    public static Connection getConnection() throws SQLException {
        if (PASSWORD == null || PASSWORD.isEmpty()) {
            throw new SQLException("""
                    未配置数据库密码（环境变量 FINANCE_DB_PASSWORD 为空）。
                    密码不写在代码里（本仓库是公开的）。两种配置方式：
                      1) 用命令行启动：bash dev.sh run <主类>  —— dev.sh 会从 dev.local.sh 读取后注入
                      2) 自己设置环境变量，或写进 IDEA 的 Run Configuration
                    """);
        }
        return DriverManager.getConnection(URL, USER, PASSWORD);
    }
}
