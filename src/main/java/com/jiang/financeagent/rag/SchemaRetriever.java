package com.jiang.financeagent.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Schema 召回器 —— RAG 四环节里的「索引 + 召回」两环。
 *
 * ============================================================
 * 一、要解决什么问题
 * ============================================================
 *   第 2~6 步的做法是：把**全部**表结构拼进 prompt。
 *   本项目只有 4 张表（约 550 token），看起来没问题。但真实企业库几百张表：
 *
 *     - token 成本随表数线性增长（100 张表 ≈ 1.4 万 token / 次）
 *     - 无关表会**干扰**模型判断（问"支出"却看到 t_account 字段，可能去 JOIN 不需要的表）
 *
 *   召回要做的就是：**只把与问题相关的几张贴进 prompt**。
 *
 * ============================================================
 * 二、为什么不用向量检索（Embedding）—— 一个实测结论
 * ============================================================
 *   工业界主流是 Embedding 向量召回（语义相似度）。能不能用，取决于有没有接口。
 *   实测（2026-10-04）：DeepSeek 的 /embeddings 接口返回 **404**，即不提供 embedding 服务。
 *
 *     POST https://api.deepseek.com/embeddings   →   HTTP 404
 *
 *   所以要接向量，得再申请智谱 / 阿里百炼的 Key。当前阶段用**关键词召回**跑通骨架，
 *   理由是：RAG 的骨架（切块→索引→召回→增强）两者**完全一样**，
 *   换的只是"相似度怎么算"这一步。
 *
 *   本类的 score() 就是预留的替换点 —— 将来写一个 EmbeddingRetriever，
 *   把 score() 内部换成余弦相似度即可，其余代码一行不用动。
 *
 * ============================================================
 * 三、中文关键词匹配怎么做（不引入分词库）
 * ============================================================
 *   英文按空格分词，中文没有空格。常见做法有三种：
 *     a) 接 jieba / HanLP 分词库        —— 准，但多一个依赖
 *     b) 用 MySQL 全文索引 ngram        —— 依赖数据库配置
 *     c) **字符 bigram（二元切分）**     —— 零依赖，对 2 字中文词效果很好 ★ 本类采用
 *
 *   "上个月支出多少" → 上个 / 个月 / 月支 / 支出 / 出多 / 多少
 *   只要 chunk 文本里出现「支出」，就能命中 —— 不需要知道"上个月"是一个词。
 *   这也是 Elasticsearch 的 CJK 分析器采用的思路。
 *
 * ============================================================
 * 四、打分的三个设计
 * ============================================================
 *   1. **按来源加权**：表名 > 表注释 > 字段名 > 字段注释。
 *      表名/表注释最能代表一张表的主题，权重必须更高。
 *
 *   2. ★ **字段真实取值给高权重（2.0）**：
 *      用户提问用的是业务词（"支出"、"餐饮"），而这些词往往就是字段取值。
 *      "上个月支出多少"能命中 t_transaction，靠的就是 status 的取值 [收入, 支出]。
 *      这是纯关键词召回里性价比最高的一招。
 *
 *   3. **不做 TF-IDF 的 IDF 项**：我们只有几张到几十张 chunk，
 *      每个词几乎都只出现在个别 chunk 里，IDF 区分度不大，反而增加复杂度。
 *      在真实大库（几百张表）里才值得加。
 *
 * ============================================================
 * 五、两阶段召回：语义召回 + 关联扩展
 * ============================================================
 *   只按词面打分有一个**真实测出来的盲区**：
 *
 *     问题："餐饮这一项花了多少钱？"
 *     语义召回只带回 t_category（"餐饮"是它的取值），
 *     但金额在 t_transaction 里，必须 JOIN —— 模型拿不到 t_transaction 就写不出 SQL。
 *
 *   原因是词面召回不理解「任务需要哪几张表」。修法是在打分之后补一跳：
 *   **用主键名做双向关系扩展**（详见 expand 方法的注释）。
 *
 *   这样 retrieve() 就变成两阶段：
 *     阶段一 retrieveByScore()  按词面得分取 topK —— 负责「找对方向」
 *     阶段二 expand()           把与已选表有引用关系的表补上 —— 负责「补全 JOIN」
 *
 *   代价是精确率：扩展会带进一些不直接相关的表。这是召回率与精确率的经典权衡，
 *   第 8 步用评测集量化后再调 MAX_RELATED。
 */
public final class SchemaRetriever {

    private SchemaRetriever() {
    }

    /** 召回结果：命中的表 + 得分 + 命中的词（命中词用于调试和演示，生产可以不打） */
    public record Hit(SchemaChunk chunk, double score, List<String> matchedTerms) {
    }

    /** 各类文本来源的权重（经验值，可用第 8 步的评测集调优） */
    private static final double W_TABLE_NAME = 3.0;
    private static final double W_TABLE_COMMENT = 2.5;
    private static final double W_COLUMN_NAME = 2.0;
    private static final double W_ENUM_VALUE = 2.0;
    private static final double W_COLUMN_COMMENT = 1.2;

    /** 默认召回张数 */
    public static final int DEFAULT_TOP_K = 3;

    /**
     * 关联扩展最多再补几张表。
     * 不设上限的话，星型结构（一张维表被十几张事实表引用）会把整库都拖进来。
     */
    public static final int MAX_RELATED = 2;

    /**
     * 对全部 chunk 打分并降序排列。
     * 返回全部（包括 0 分的）—— 由调用方决定要不要过滤，方便调试时看清"为什么没召回它"。
     */
    public static List<Hit> score(String question, List<SchemaChunk> chunks) {
        Set<String> queryTerms = terms(question);

        List<Hit> hits = new ArrayList<>(chunks.size());
        for (SchemaChunk chunk : chunks) {
            Map<String, Double> index = index(chunk);
            double score = 0;
            List<String> matched = new ArrayList<>();

            for (String term : queryTerms) {
                Double weight = index.get(term);
                if (weight != null) {
                    score += weight;
                    matched.add(term);
                }
            }
            hits.add(new Hit(chunk, score, matched));
        }

        // 分数降序；同分时按表名字典序，保证结果稳定可复现
        hits.sort(Comparator.comparingDouble(Hit::score).reversed()
                .thenComparing(h -> h.chunk().table()));
        return hits;
    }

    /**
     * 召回入口（两阶段）：语义召回 + 关联扩展。
     *
     * 【兜底策略：一张都没命中时返回全部】
     *   宁可多给（多花点 token），也不能让模型"无表可用"。
     *   模型再聪明，看不见表也写不出 SQL。
     */
    public static List<SchemaChunk> retrieve(String question, List<SchemaChunk> chunks, int topK) {
        List<SchemaChunk> picked = retrieveByScore(question, chunks, topK);
        if (picked.isEmpty()) {
            return List.copyOf(chunks);     // 全库兜底
        }
        return expand(picked, chunks);
    }

    /**
     * 阶段一：纯词面得分取 topK（得分必须 > 0）。
     *
     * 单独暴露出来是为了让第 7 步的验证程序能分别打印两个阶段，
     * 看清「扩展」到底补了什么、代价是多少。
     */
    public static List<SchemaChunk> retrieveByScore(String question, List<SchemaChunk> chunks, int topK) {
        List<SchemaChunk> picked = new ArrayList<>();
        for (Hit hit : score(question, chunks)) {
            if (hit.score() <= 0) {
                break;                      // 已按分数降序，遇到 0 分即可停
            }
            if (picked.size() >= topK) {
                break;
            }
            picked.add(hit.chunk());
        }
        return picked;
    }

    /**
     * 阶段二：基于「主键名」的双向一跳关联扩展。
     *
     * ============================================================
     * 【为什么需要它 —— 一个实测的失败案例】
     * ============================================================
     *   问题："餐饮这一项花了多少钱？"
     *   语义召回只带回 t_category（"餐饮"是它的取值），但金额在 t_transaction 里。
     *   模型拿到一张没有金额字段的维表，写不出任何能回答问题的 SQL。
     *
     *   根因：词面召回只回答"哪个表跟这些词像"，不回答"回答这个问题需要哪几张表"。
     *
     * ============================================================
     * 【为什么用「主键名」而不是外键约束】
     * ============================================================
     *   查 information_schema 的外键约束当然更"正规"，但真实项目里
     *   外键几乎都不建（影响写入性能、分库分表不支持），约束信息根本不可用。
     *   而命名约定几乎总是遵守的：<表名>_id。
     *   所以用「主键名匹配」来推断引用关系，比外键约束更通用。
     *
     *   （这正是 dataagent 里「表关系推断」节点的简化版 ——
     *     它做得更细：会分析 JOIN 路径、评估可行性。我们这里只做一跳。）
     *
     * ============================================================
     * 【双向扩展的两个方向】
     * ============================================================
     *   已选 t_category（主键 category_id）：
     *
     *   方向 A「谁引用了我」：找含 category_id 字段的表
     *     → t_transaction ✓   （用户问分类相关的问题，明细表通常也要）
     *
     *   方向 B「我引用了谁」：找主键出现在已选表 *_id 字段里的表
     *     已选 t_transaction 的 account_id / category_id
     *     → t_account（主键 account_id）✓、t_category（主键 category_id）✓
     *     （用户问交易明细，模型多半还要展示账户名/分类名）
     */
    public static List<SchemaChunk> expand(List<SchemaChunk> picked, List<SchemaChunk> chunks) {
        Set<String> pickedNames = new HashSet<>();
        /** 已选表的主键名 —— 用于方向 A */
        Set<String> pickedPrimaryKeys = new HashSet<>();
        /** 已选表引用的字段名（*_id）—— 用于方向 B */
        Set<String> pickedReferences = new HashSet<>();

        for (SchemaChunk chunk : picked) {
            pickedNames.add(chunk.table());
            for (SchemaChunk.Column column : chunk.columns()) {
                String name = column.name().toLowerCase(Locale.ROOT);
                if (column.primaryKey()) {
                    pickedPrimaryKeys.add(name);
                } else if (name.endsWith("_id")) {
                    pickedReferences.add(name);
                }
            }
        }

        List<SchemaChunk> related = new ArrayList<>();
        for (SchemaChunk chunk : chunks) {          // 按库中顺序遍历，保证结果稳定
            if (pickedNames.contains(chunk.table())) {
                continue;
            }
            for (SchemaChunk.Column column : chunk.columns()) {
                String name = column.name().toLowerCase(Locale.ROOT);
                boolean referencesMe = pickedPrimaryKeys.contains(name);
                boolean iReference = column.primaryKey() && pickedReferences.contains(name);
                if (referencesMe || iReference) {
                    related.add(chunk);
                    break;
                }
            }
        }

        List<SchemaChunk> result = new ArrayList<>(picked);
        for (int i = 0; i < related.size() && i < MAX_RELATED; i++) {
            result.add(related.get(i));
        }
        return result;
    }

    // ---------------------------------------------------------------
    // 索引：把一张表摊平成「词 → 权重」的映射
    // ---------------------------------------------------------------

    private static Map<String, Double> index(SchemaChunk chunk) {
        Map<String, Double> index = new HashMap<>();
        put(index, chunk.table(), W_TABLE_NAME);
        put(index, chunk.comment(), W_TABLE_COMMENT);

        for (SchemaChunk.Column column : chunk.columns()) {
            put(index, column.name(), W_COLUMN_NAME);
            for (String value : column.values()) {
                put(index, value, W_ENUM_VALUE);
            }
            put(index, column.comment(), W_COLUMN_COMMENT);
        }
        return index;
    }

    /** 把一段文本切成词，按 weight 累加进索引（同一词出现在多处则权重叠加） */
    private static void put(Map<String, Double> index, String text, double weight) {
        for (String term : terms(text)) {
            index.merge(term, weight, Double::sum);
        }
    }

    /**
     * 文本 → 词集合。同时产出英文词和中文 bigram，覆盖两种语言。
     *
     *   英文："t_transaction"  → [transaction]      （单字母 t 丢弃）
     *   中文："交易记录表"      → [交易, 易记, 记录, 录表]
     */
    static Set<String> terms(String text) {
        Set<String> result = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return result;
        }
        String lower = text.toLowerCase(Locale.ROOT);

        // ① 英文/数字词：按非字母数字切分
        for (String token : lower.split("[^a-z0-9]+")) {
            if (token.length() >= 2) {
                result.add(token);
            }
        }

        // ② 中文 bigram：先只保留汉字，再逐两字滑窗
        for (String segment : lower.replaceAll("[^\\u4e00-\\u9fa5]", " ").split("\\s+")) {
            if (segment.isEmpty()) {
                continue;
            }
            if (segment.length() == 1) {
                result.add(segment);            // 单字词（如"钱"）保留，否则永远匹配不上
                continue;
            }
            for (int i = 0; i + 2 <= segment.length(); i++) {
                result.add(segment.substring(i, i + 2));
            }
        }
        return result;
    }
}
