package com.jiang.financeagent.tool;

import com.jiang.financeagent.db.Db;
import com.jiang.financeagent.rag.SchemaChunk;
import com.jiang.financeagent.rag.SchemaRetriever;
import com.jiang.financeagent.util.Text;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 工具一：getDatabaseSchema —— 让模型「看见」数据库长什么样。
 *
 * 【为什么这是第一个工具】
 *   模型不知道你有哪些表、哪些字段叫什么。你不告诉它，它只能靠猜 —— 猜出来的 SQL
 *   会用在根本不存在的字段上。所以「给模型看表结构」是 text2SQL 的地基。
 *
 * 【为什么不直接把建表语句丢给模型】
 *   因为要花 token（= 花钱），而且会泄漏不该给的东西。这个库只有 6 张表还好；
 *   真实企业库几百张表，全塞进去既超上下文窗口又贵。
 *   所以这里做了三件事：压缩输出、过滤敏感字段、注入字段真实取值。
 *
 * ============================================================
 * 第 7 步的改造：从「全量导出」升级为「RAG 召回」
 * ============================================================
 *   原来只有一个方法 describeSchema()，把全部表拼进 prompt。
 *   现在拆成三步，对应 RAG 的骨架：
 *
 *     chunks()                  ① 切块 + 索引：读库构建 SchemaChunk 列表，**进程内缓存**
 *     describeSchema()          ②a 全量渲染（逃生舱 / 表少时用）
 *     describeRelevantSchema(q) ②b 召回后只渲染相关的几张表
 *
 *   为什么切块要缓存？构建索引要跑「读 information_schema + 逐字段采样取值」
 *   十几条 SQL。表结构几乎不变，每次提问都重跑一遍纯属浪费。
 */
public final class SchemaTool {

    /** 从 information_schema 读字段（这是 MySQL 自带的元数据库，不是业务数据） */
    private static final String SQL_COLUMNS = """
            SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, COLUMN_KEY, COLUMN_COMMENT
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = ?
            ORDER BY TABLE_NAME, ORDINAL_POSITION
            """;

    private static final String SQL_TABLES = """
            SELECT TABLE_NAME, TABLE_COMMENT
            FROM information_schema.TABLES
            WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE'
            """;

    /**
     * 【第一道过滤】敏感字段：结构不列出、取值不采样，完全不出现在 prompt 里。
     *
     * 这一条是实测发现的，不是凭空想出来的：最初版本把 user 表的所有 varchar 字段
     * 都做了取值采样，结果 prompt 里出现了
     *     user.password = xiaoye / admin
     * 而这段 prompt 是要通过网络发给 DeepSeek 的 —— 等于把用户密码寄给第三方。
     *
     * 教训：工具的「输出」和工具的「能力」一样需要审查。
     *      凡是会被送给外部模型的数据，都必须先过一遍白名单/黑名单。
     */
    private static final Set<String> SENSITIVE_COLUMNS = Set.of(
            "password", "passwd", "pwd", "salt", "token", "secret", "apikey",
            "telephone", "phone", "mobile", "idcard", "email", "address"
    );

    /**
     * 【第二道过滤】展示型字段：表结构里保留（模型该知道它们存在），
     * 但不采样取值 —— 它们的值对写 SQL 毫无帮助，纯烧 token。
     */
    private static final Set<String> NO_ENUM_COLUMNS = Set.of(
            "icon", "description", "remark", "img", "image", "url", "path", "comment"
    );

    /** 单列不同取值超过这个数量就不注入（避免 token 爆炸） */
    private static final int MAX_ENUM_VALUES = 10;

    /**
     * chunk 缓存。
     *
     * 【为什么用 volatile + 双重检查】
     *   典型的懒加载单例写法。理论上 Agent 是单线程，加锁意义不大，
     *   但"表结构变更后要重启进程"这件事必须明确 —— 写在这里比写注释更醒目。
     *   真要支持热更新，可以在 SqlTool 里监听 DDL，或加一个刷新入口。
     */
    private static volatile List<SchemaChunk> cachedChunks;

    private SchemaTool() {
    }

    // ============================================================
    // ① 切块 + 索引
    // ============================================================

    /** 取全部 chunk（首次调用构建并缓存） */
    public static List<SchemaChunk> chunks() throws SQLException {
        List<SchemaChunk> local = cachedChunks;
        if (local == null) {
            synchronized (SchemaTool.class) {
                local = cachedChunks;
                if (local == null) {
                    local = buildChunks();
                    cachedChunks = local;
                }
            }
        }
        return local;
    }

    /** 清空缓存（改过表结构后调试用） */
    public static void invalidateCache() {
        cachedChunks = null;
    }

    private static List<SchemaChunk> buildChunks() throws SQLException {
        try (Connection conn = Db.getConnection()) {
            Map<String, String> tableComments = loadTableComments(conn);
            Map<String, List<String[]>> columns = loadColumns(conn);

            List<SchemaChunk> chunks = new ArrayList<>();
            for (Map.Entry<String, List<String[]>> entry : columns.entrySet()) {
                String table = entry.getKey();
                List<SchemaChunk.Column> cols = new ArrayList<>();
                for (String[] c : entry.getValue()) {
                    cols.add(new SchemaChunk.Column(
                            c[0],                                   // 字段名
                            c[1],                                   // 类型
                            "PRI".equals(c[2]),                     // 是否主键
                            c[3],                                   // 注释
                            sampleValues(conn, table, c[0], c[1])   // 真实取值
                    ));
                }
                chunks.add(new SchemaChunk(table, tableComments.getOrDefault(table, ""), List.copyOf(cols)));
            }
            return List.copyOf(chunks);
        }
    }

    // ============================================================
    // ② 渲染
    // ============================================================

    /** 全量：把所有可访问表拼成 prompt 文本（第 2~6 步的原有行为，作为逃生舱保留） */
    public static String describeSchema() throws SQLException {
        List<SchemaChunk> all = chunks();
        return render(all, "【数据库 " + Db.DATABASE + " 的表结构】");
    }

    /**
     * 召回：只把与问题相关的表拼成 prompt 文本。
     *
     * 【兜底与逃生舱】
     *   1. 召回为空 → SchemaRetriever 内部返回全部表（宁可多给，不能让模型无表可用）
     *   2. 返回文本末尾附一句"如需其他表，可再次调用并留空 question" ——
     *      给模型一条退路。召回不可能 100% 准，关键是要有恢复手段。
     */
    public static String describeRelevantSchema(String question) throws SQLException {
        return describeRelevantSchema(question, SchemaRetriever.DEFAULT_TOP_K);
    }

    public static String describeRelevantSchema(String question, int topK) throws SQLException {
        List<SchemaChunk> all = chunks();

        // 问题为空 → 等价于"我要全部"
        if (question == null || question.isBlank()) {
            return render(all, "【数据库 " + Db.DATABASE + " 的表结构（全量）】");
        }

        List<SchemaChunk> byScore = SchemaRetriever.retrieveByScore(question, all, topK);
        // 语义阶段一张都没命中 → 全库兜底（宁可多给，不能让模型无表可用）
        boolean noKeywordHit = byScore.isEmpty();
        List<SchemaChunk> picked = noKeywordHit ? all : SchemaRetriever.expand(byScore, all);

        // 覆盖全库有两种完全不同的原因，必须分开说 —— 否则排查时会被误导：
        //   a) 关键词一个都没命中           → 召回没起作用，是兜底
        //   b) 关键词命中了但关联扩展扩到全库 → 召回起作用了，只是这个库太小
        // 本项目（4 张表）属于 b：表少时召回退化为全量是**正确行为** ——
        // 全量才 547 token，为省这点钱去冒漏表的风险不划算。召回的价值在表多时才体现。
        boolean coveredAll = picked.size() == all.size();
        String summary;
        if (noKeywordHit) {
            summary = "未命中关键词，已返回全部";
        } else if (coveredAll) {
            summary = "语义命中 " + byScore.size() + " 张，关联扩展后已覆盖全部";
        } else {
            summary = "已召回 " + picked.size() + " 张";
        }

        String header = "【与问题相关的表结构】（库中共 " + all.size() + " 张表，" + summary
                + "：" + String.join(", ", picked.stream().map(SchemaChunk::table).toList()) + "）";

        // ★ 只在「真的只召回了一部分」时才提示逃生舱。
        //   实测踩过的坑：已经给了全部表还提示"可调用 getFullSchema 获取全部"，
        //   模型会照做 —— 白白多一次 API 调用，拿到的还是同一份内容。
        //   提示词要跟着实际状态走，不能无条件挂着。
        if (!coveredAll) {
            header += "\n若上述表不足（例如缺少需要 JOIN 的表），可调用 getFullSchema 获取全部表结构。";
        }

        return render(picked, header);
    }

    /** 把 chunk 列表渲染成 prompt 文本（表结构 + 字段取值范围） */
    private static String render(List<SchemaChunk> chunks, String header) {
        StringBuilder sb = new StringBuilder(header).append("\n\n");

        for (SchemaChunk chunk : chunks) {
            sb.append("表 ").append(chunk.table());
            if (!chunk.comment().isBlank()) {
                sb.append("（").append(chunk.comment()).append("）");
            }
            sb.append('\n');

            for (SchemaChunk.Column col : chunk.columns()) {
                sb.append("  ").append(Text.padRight(col.name(), 18))
                        .append(Text.padRight(col.type(), 16))
                        .append(col.primaryKey() ? "PK " : "   ");
                if (!col.comment().isBlank()) {
                    sb.append("-- ").append(col.comment());
                }
                sb.append('\n');
            }
            sb.append('\n');
        }

        sb.append(describeEnumValues(chunks));
        return sb.toString();
    }

    /**
     * 注入低基数字段的真实取值。
     *
     * 【为什么这一步是 text2SQL 的关键】
     *   你库里 t_transaction.status 存的是中文「收入 / 支出」。
     *   如果只把字段名告诉模型，它极可能写出 WHERE status = 1 或 WHERE status = 'income'，
     *   SQL 语法没问题，但查出来永远是空 —— 这类错误最难排查。
     *   把真实取值给它看，它就不可能写错。
     */
    private static String describeEnumValues(List<SchemaChunk> chunks) {
        StringBuilder sb = new StringBuilder("【字段取值范围】（生成 SQL 时必须使用下列真实值）\n");
        for (SchemaChunk chunk : chunks) {
            for (SchemaChunk.Column col : chunk.columns()) {
                if (col.values().isEmpty()) {
                    continue;
                }
                sb.append("  ").append(chunk.table()).append('.').append(col.name())
                        .append(" = ").append(String.join(" / ", col.values())).append('\n');
            }
        }
        return sb.toString();
    }

    // ============================================================
    // 元数据读取（原有逻辑，未改动）
    // ============================================================

    /** 读字段；顺手过滤掉敏感字段，让它们根本进不了内存 */
    private static Map<String, List<String[]>> loadColumns(Connection conn) throws SQLException {
        Map<String, List<String[]>> result = new LinkedHashMap<>();
        try (var ps = conn.prepareStatement(SQL_COLUMNS)) {
            ps.setString(1, Db.DATABASE);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String tableName = rs.getString("TABLE_NAME");
                    // ★ 只展示「能访问的」表：与 SqlGuard.ALLOWED_TABLES 共用同一份清单。
                    //   否则模型会看到 user / role 却查不了，白白浪费一次调用。
                    if (!SqlGuard.ALLOWED_TABLES.contains(tableName.toLowerCase(Locale.ROOT))) {
                        continue;
                    }
                    String columnName = rs.getString("COLUMN_NAME");
                    if (isSensitive(columnName)) {
                        continue;
                    }
                    result.computeIfAbsent(tableName, k -> new ArrayList<>())
                            .add(new String[]{
                                    columnName,
                                    rs.getString("COLUMN_TYPE"),
                                    rs.getString("COLUMN_KEY"),
                                    rs.getString("COLUMN_COMMENT")
                            });
                }
            }
        }
        return result;
    }

    private static Map<String, String> loadTableComments(Connection conn) throws SQLException {
        Map<String, String> result = new LinkedHashMap<>();
        try (var ps = conn.prepareStatement(SQL_TABLES)) {
            ps.setString(1, Db.DATABASE);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getString("TABLE_NAME"), rs.getString("TABLE_COMMENT"));
                }
            }
        }
        return result;
    }

    private static boolean isSensitive(String columnName) {
        return SENSITIVE_COLUMNS.contains(columnName.toLowerCase(Locale.ROOT));
    }

    /** 采样取值；不满足条件的字段返回空列表（不渲染、也不进索引） */
    private static List<String> sampleValues(Connection conn, String table, String column, String type) {
        if (NO_ENUM_COLUMNS.contains(column.toLowerCase(Locale.ROOT))) {
            return List.of();
        }
        // 只采样字符串型字段（数字型的取值范围没有枚举意义）
        if (!type.startsWith("varchar") && !type.startsWith("char")) {
            return List.of();
        }
        List<String> values = distinctValues(conn, table, column);
        if (values.isEmpty() || values.size() > MAX_ENUM_VALUES) {
            return List.of();
        }
        return values;
    }

    private static List<String> distinctValues(Connection conn, String table, String column) {
        List<String> values = new ArrayList<>();
        // 安全说明：table / column 来自 information_schema（数据库自身的元数据），
        // 不是用户输入，因此这里拼接标识符不存在 SQL 注入风险。
        // 用户输入的部分（后续步骤的问答内容）一律走 PreparedStatement。
        String sql = "SELECT DISTINCT `" + column + "` FROM `" + table + "`"
                + " WHERE `" + column + "` IS NOT NULL LIMIT " + (MAX_ENUM_VALUES + 1);
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                values.add(rs.getString(1));
            }
        } catch (SQLException e) {
            return List.of();
        }
        return values;
    }
}
