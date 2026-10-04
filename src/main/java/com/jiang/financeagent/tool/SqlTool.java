package com.jiang.financeagent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jiang.financeagent.db.Db;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;

/**
 * 工具二：executeReadOnlySql —— 执行模型生成的只读 SQL，返回 JSON 结果。
 *
 * ============================================================
 * 关键设计一：错误要「返回」而不是「抛出」
 * ============================================================
 *   如果 SQL 执行失败就抛异常，这一轮对话就断了，模型永远不知道发生了什么。
 *   正确做法是把错误信息当成**工具的执行结果**回传给模型：
 *
 *     模型生成 → 执行失败「Unknown column 'createTime'」→ 回传 → 模型看到列名写错了
 *              → 改成 create_time 重新生成 → 成功
 *
 *   这就是「SQL 失败自动重试」的基础。Agent 能自愈，靠的就是错误信息能被模型看到。
 *
 * ============================================================
 * 关键设计二：错误要「可行动」——分类 + 修正建议（第 6 步新增）
 * ============================================================
 *   只回传原始报错还不够。模型看到 "Unknown column 'createTime'" 能懂，但不知道该换成什么。
 *   所以这里把 MySQL 错误码翻译成 errorType + hint，直接告诉它下一步怎么改。
 *
 *   另一类必须区分出来的是「被安全策略拒绝」：
 *   这不是 SQL 写错了，而是这条路根本不允许走。必须明确告诉模型"不要试图改写绕过"，
 *   否则它会不停换写法去撞白名单，白白烧 token。
 *
 * ============================================================
 * 关键设计三：三层防线叠加
 * ============================================================
 *   第 1 层  本方法入口调用 SqlGuard   —— 拦住语法层面的危险（只读、白名单、行数上限）
 *   第 2 层  setQueryTimeout           —— 拦住跑太久的查询
 *   第 3 层  ai_readonly 只读账号      —— 即使前两层全被绕过，写操作仍会被数据库拒绝
 */
public final class SqlTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 单条 SQL 最长执行秒数，防止一条慢查询占死连接 */
    private static final int QUERY_TIMEOUT_SECONDS = 10;

    private SqlTool() {
    }

    /**
     * 执行只读 SQL。
     *
     * @param sql 模型生成的 SQL（未经验证的原始输入）
     * @return JSON 字符串。成功形如 {"columns":[...],"rows":[...],"rowCount":3}
     * 失败形如 {"error":"...","errorType":"...","hint":"..."}
     * —— 注意失败也是正常返回值，不是异常
     */
    public static String executeReadOnlySql(String sql) {
        // ---- 第 1 层：代码层安全校验 ----
        SqlGuard.Verdict verdict = SqlGuard.check(sql);
        if (!verdict.allowed()) {
            return guardRejected(verdict.category(), verdict.reason());
        }

        long start = System.currentTimeMillis();
        try (Connection conn = Db.getConnection();
             Statement statement = conn.createStatement()) {

            // ---- 第 2 层：超时保护 ----
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);

            try (ResultSet rs = statement.executeQuery(verdict.safeSql())) {
                ObjectNode result = toJson(rs);
                result.put("elapsedMs", System.currentTimeMillis() - start);
                result.put("executedSql", verdict.safeSql());
                return MAPPER.writeValueAsString(result);
            }

        } catch (SQLException e) {
            // ---- 把数据库的报错翻译成可行动的修正建议，交回给模型 ----
            String[] classified = classify(e);
            return error("SQL 执行失败：" + e.getMessage(), classified[0], classified[1]);
        } catch (Exception e) {
            return error("执行异常：" + e.getMessage(),
                    "TOOL_EXCEPTION",
                    "工具内部异常，可尝试重新调用一次");
        }
    }

    /**
     * 安全校验被拒时的错误整形。
     *
     * 【为什么要按 Category 分开写提示】
     *   实测发现：如果所有拒绝都给同一句「不要试图绕过」，
     *   模型会把「表名拼错了」也当成安全红线，直接放弃、不再尝试修正 ——
     *   而这恰恰是它**应该**去改的。
     *   所以这里按分类给对症建议：
     *     能改的（语法/多语句/表名疑似拼错）→ 鼓励它修正后重试
     *     不能改的（写操作/危险语法）        → 明确叫停，让它如实告知用户
     */
    private static String guardRejected(SqlGuard.Category category, String reason) {
        String prefix = "安全检查未通过：" + reason;
        return switch (category) {
            case SYNTAX -> error(prefix, "INVALID_SQL",
                    "SQL 语法无法解析。请检查括号是否配对、字符串引号是否闭合、关键字顺序是否正确，然后重试");

            case MULTI_STATEMENT -> error(prefix, "MULTI_STATEMENT",
                    "一次只能执行一条 SQL。请把语句拆开，只保留需要的 SELECT 后重试");

            case TABLE_NOT_ALLOWED -> error(prefix, "TABLE_NOT_ALLOWED",
                    "该表不在允许访问的范围内。先核对是否拼写错误——可调用 getDatabaseSchema 查看可用表："
                            + "t_transaction / t_account / t_category / t_budget_type。"
                            + "如果确实想访问 user / role 这类敏感表，则不被允许，请如实告知用户");

            default -> error(prefix, "POLICY_REJECTED",
                    "这是安全策略拒绝，不是 SQL 写法问题（例如写操作、多语句、危险函数）。"
                            + "不要试图改写 SQL 绕过它，请改用合法的只读查询，"
                            + "或直接如实告知用户该操作不被允许");
        };
    }

    /**
     * 把 MySQL 报错翻译成 errorType + 修正建议。
     *
     * MySQL 错误码对照：https://dev.mysql.com/doc/mysql-errors/8.0/en/server-error-reference.html
     * 这里只覆盖我们实际会遇到的几类，其余归入 SQL_ERROR 并给通用建议。
     */
    private static String[] classify(SQLException e) {
        return switch (e.getErrorCode()) {
            case 1054 -> new String[]{
                    "UNKNOWN_COLUMN",
                    "列名不存在。请调用 getDatabaseSchema 核对真实字段名——本项目字段名是下划线风格"
                            + "（如 create_time 而不是 createTime）"};
            case 1052 -> new String[]{
                    "AMBIGUOUS_COLUMN",
                    "字段名在多张表之间产生歧义。请给表加别名，并用 别名.字段名 的方式引用"};
            case 1064 -> new String[]{
                    "SYNTAX_ERROR",
                    "SQL 语法错误。请检查括号是否配对、字符串引号是否闭合、关键字顺序是否正确"};
            case 1146 -> new String[]{
                    "UNKNOWN_TABLE",
                    "表不存在。请调用 getDatabaseSchema 查看允许访问的表："
                            + "t_transaction / t_account / t_category / t_budget_type"};
            case 1142, 1143 -> new String[]{
                    "PERMISSION_DENIED",
                    "数据库账号权限不足。本 Agent 使用只读账号，只能执行 SELECT"};
            case 1241 -> new String[]{
                    "COLUMN_COUNT_MISMATCH",
                    "UNION 或子查询两侧的列数不一致，请对齐列数"};
            case 1247 -> new String[]{
                    "ILLEGAL_REFERENCE",
                    "引用了不存在的表别名或字段别名，请检查 AS 别名与引用是否一致"};
            case 1221 -> new String[]{
                    "INVALID_OPERATION",
                    "该操作不允许用于当前语句，请改用合法的聚合或筛选写法"};
            default -> new String[]{
                    "SQL_ERROR",
                    e.getSQLState() != null && e.getSQLState().startsWith("42")
                            ? "语法或对象引用问题，请核对字段名与 SQL 写法"
                            : "请检查 SQL，必要时重新调用 getDatabaseSchema 确认表结构"};
        };
    }

    /** 结果集 → JSON。列名用 columnLabel（这样 SELECT ... AS 月份 能拿到中文别名） */
    private static ObjectNode toJson(ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int columnCount = meta.getColumnCount();

        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode columns = root.putArray("columns");
        for (int i = 1; i <= columnCount; i++) {
            columns.add(meta.getColumnLabel(i));
        }

        ArrayNode rows = root.putArray("rows");
        int count = 0;
        boolean truncated = false;
        while (rs.next()) {
            // 取回 MAX_ROWS + 1 行；多出来的这一行说明结果被截断了
            if (count >= SqlGuard.MAX_ROWS) {
                truncated = true;
                break;
            }
            ArrayNode row = rows.addArray();
            for (int i = 1; i <= columnCount; i++) {
                appendValue(row, rs.getObject(i));
            }
            count++;
        }

        root.put("rowCount", count);
        if (truncated) {
            root.put("truncated", true);
            root.put("note", "结果超过 " + SqlGuard.MAX_ROWS + " 行已截断，建议在 SQL 里加聚合或更严格的筛选条件");
        }
        return root;
    }

    /** 按类型写入 JSON，保证金额精度不丢（BigDecimal 直接写，不转 double） */
    private static void appendValue(ArrayNode row, Object value) {
        if (value == null) {
            row.addNull();
        } else if (value instanceof BigDecimal decimal) {
            row.add(decimal);
        } else if (value instanceof Integer || value instanceof Long) {
            row.add(((Number) value).longValue());
        } else if (value instanceof Number number) {
            row.add(number.doubleValue());
        } else if (value instanceof Timestamp timestamp) {
            row.add(timestamp.toLocalDateTime().toString());
        } else {
            row.add(String.valueOf(value));
        }
    }

    /** 结构化错误：error 给人看，errorType 给程序判断，hint 告诉模型下一步怎么改 */
    private static String error(String message, String errorType, String hint) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("error", message == null ? "未知错误" : message);
        node.put("errorType", errorType);
        node.put("hint", hint);
        return node.toString();
    }
}
