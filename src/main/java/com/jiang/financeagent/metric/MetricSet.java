package com.jiang.financeagent.metric;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 语义层：业务指标（口径）的加载、匹配与注入。
 *
 * ============================================================
 * 这个类解决的问题：口径散落在提示词里
 * ============================================================
 *   在加语义层之前，generate 节点的提示词里写着这样一段自然语言规则：
 *
 *     问「支出多少」→ WHERE status = '支出' 之后 SUM(amount)
 *     问「收入多少」→ WHERE status = '收入' 之后 SUM(amount)
 *     **只有**问「净额 / 结余 / 收入减支出 / 还剩多少」时，才用
 *       SUM(CASE WHEN status = '收入' THEN amount ELSE -amount END)
 *
 *   它能工作，但有三个结构性缺陷：
 *     1. **用户说法和口径不是一一对应**。问「我赚了多少」，可能是「收入」，
 *        也可能是「净结余」—— 而这段规则只列了「净额 / 结余」几个词，
 *        换个说法就漏。
 *     2. **改口径要动代码**。想把「净结余」的定义改成"不含转账"，得改 Java、重新编译。
 *     3. **没有单一事实来源**。问"「结余」到底怎么算的"，
 *        答案散在一段几百字的提示词里。
 *
 *   现在口径变成 config/metrics.json 里的结构化定义：
 *     改配置即生效（不重新编译）、匹配过程可单测、口径有唯一出处。
 *
 * ============================================================
 * 关键设计：匹配用规则，不用模型
 * ============================================================
 *   「这个问题命中了哪个指标」本质是词面匹配（问里有没有出现别名），
 *   是**确定性的字符串判断**。让模型来做，等于把一件能算清楚的事
 *   交给概率模型 —— 和第 10 步 verify 节点的判断完全同一个道理：
 *   **能用规则检查的，就不该用模型检查。**
 *
 *   所以这一步是零 token 的：它只是把匹配到的口径**注入**给 generate，
 *   让模型从"理解业务口径"降级为"把已定好的表达式填进 SQL"。
 *
 * ============================================================
 * 降级策略：配置坏了不能让问答挂掉
 * ============================================================
 *   文件不存在、JSON 语法错、字段缺失 —— 任何一种情况都降级为"无指标模式"，
 *   行为等价于加语义层之前（提示词里仍保留 status 的原始说明）。
 *   理由：语义层是"提高准确率"，不是"能不能跑"的前提。
 *   一个可选优化不该成为可用性的单点。
 */
public final class MetricSet {

    /** 一个业务指标。字段含义见 config/metrics.json 里的说明 */
    public record Metric(
            String name,
            List<String> aliases,
            String expression,
            String where,
            String description,
            int priority,
            List<String> supersedes) {

        /** 问题里出现任一别名即视为命中 */
        boolean matches(String question) {
            return aliases.stream().anyMatch(question::contains);
        }
    }

    private static final ObjectMapper M = new ObjectMapper();
    private static final String DEFAULT_FILE = "config/metrics.json";
    /**
     * 没有命中指标时的兜底说明。
     *
     * 【为什么兜底里还留着口径，而不是完全清空】
     *   因为「关掉语义层」必须等价于「加语义层之前的行为」——
     *   否则 A/B 对比就没有可比性，也无法安全回滚。
     *   这段话就是把原先写在 generate 提示词里的那几行搬到了这里，
     *   将来若要从配置文件完全驱动，删掉它即可（那才是真正的"口径只在配置里"）。
     */
    private static final String EMPTY_BLOCK = """
            （本次问题没有匹配到预定义的业务指标。
             若涉及金额，请按以下事实自行判断：
             · status 是中文「收入」/「支出」，amount 恒为正数，方向由 status 决定
             · 需要净额时写作 SUM(CASE WHEN status = '收入' THEN amount ELSE -amount END)）""";

    private final List<Metric> metrics;
    private final List<String> skipWhen;
    private final String table;
    private final String loadNote;

    private MetricSet(List<Metric> metrics, List<String> skipWhen, String table, String loadNote) {
        this.metrics = metrics;
        this.skipWhen = skipWhen;
        this.table = table;
        this.loadNote = loadNote;
    }

    // ---------------- 加载 ----------------

    /**
     * 按默认约定加载。
     *
     * 两个系统属性（给测试和消融实验用，不写进配置文件）：
     *   -Dfinance.metrics.file=<路径>   指定另一个配置文件
     *   -Dfinance.metrics=off          关闭语义层（用于 A/B 对比）
     */
    public static MetricSet load() {
        if ("off".equalsIgnoreCase(System.getProperty("finance.metrics", "").trim())) {
            return new MetricSet(List.of(), List.of(), "", "已通过 -Dfinance.metrics=off 显式关闭");
        }

        Path path = Path.of(System.getProperty("finance.metrics.file", DEFAULT_FILE));
        if (!Files.isRegularFile(path)) {
            return new MetricSet(List.of(), List.of(), "",
                    "未找到指标配置 " + path.toAbsolutePath() + "，降级为无指标模式");
        }

        try {
            JsonNode root = M.readTree(Files.readString(path, StandardCharsets.UTF_8));
            String table = root.path("table").asText("");
            List<String> skipWhen = textList(root.path("skipWhen"));

            List<Metric> list = new ArrayList<>();
            for (JsonNode node : root.path("metrics")) {
                String name = node.path("name").asText("").trim();
                if (name.isEmpty()) {
                    continue;                       // 没有名字的条目无意义，跳过而不是报错
                }
                list.add(new Metric(
                        name,
                        textList(node.path("aliases")),
                        node.path("expression").asText("").trim(),
                        node.path("where").asText("").trim(),
                        node.path("description").asText("").trim(),
                        node.path("priority").asInt(0),
                        textList(node.path("supersedes"))));
            }
            return new MetricSet(List.copyOf(list), skipWhen, table,
                    "已加载 " + list.size() + " 个指标、" + skipWhen.size() + " 个不适用词：" + path);
        } catch (IOException e) {
            return new MetricSet(List.of(), List.of(), "",
                    "指标配置解析失败（" + e.getMessage() + "），降级为无指标模式");
        }
    }

    private static List<String> textList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                String text = item.asText("").trim();
                if (!text.isEmpty()) {
                    out.add(text);
                }
            }
        }
        return List.copyOf(out);
    }

    // ---------------- 匹配 ----------------

    /**
     * 问题命中了哪些指标。
     *
     * 【为什么要处理 supersedes】
     *   「收入减掉支出还剩多少」这句话里**同时出现了**「收入」「支出」「还剩多少」
     *   三个别名 —— 会命中三个指标。但这句话问的是净额，只能有一个口径。
     *   如果三个表达式都注入，模型很可能既算 SUM(amount) 又算 CASE WHEN，
     *   口径直接打架。
     *
     *   所以配置里让「净结余」声明 supersedes: ["收入", "支出"]，
     *   命中净结余时把这两个基础指标从结果里吸收掉。
     *   —— 这仍然是**规则**，不是让模型去猜该用哪个。
     */
    public List<Metric> match(String question) {
        if (question == null || question.isBlank() || metrics.isEmpty()) {
            return List.of();
        }
        if (skipReason(question) != null) {
            return List.of();                       // 问的不是总额（问个数、问极值…），不注入
        }

        List<Metric> hit = new ArrayList<>();
        for (Metric metric : metrics) {
            if (metric.matches(question)) {
                hit.add(metric);
            }
        }
        if (hit.isEmpty()) {
            return List.of();
        }

        Set<String> absorbed = new LinkedHashSet<>();
        for (Metric metric : hit) {
            absorbed.addAll(metric.supersedes());
        }

        List<Metric> result = new ArrayList<>();
        for (Metric metric : hit) {
            if (!absorbed.contains(metric.name())) {
                result.add(metric);
            }
        }
        result.sort(Comparator.comparingInt(Metric::priority).reversed());
        return List.copyOf(result);
    }

    /** 命中数量（诊断用） */
    public int hitCount(String question) {
        return match(question).size();
    }

    /**
     * 问题是否因为 skipWhen 而不适用指标。
     * 返回命中的那个词（用于诊断"为什么这条没注入指标"），不适用时返回 null。
     *
     * 存在这个方法的理由：诊断"该命中却没命中"和"命中了对不对"是两类问题，
     * 只看到 match() 返回空是分不清的 —— 和第 10 步踩过的那个坑同一性质。
     */
    public String skipReason(String question) {
        if (question == null || question.isBlank()) {
            return null;
        }
        for (String word : skipWhen) {
            if (question.contains(word)) {
                return word;
            }
        }
        return null;
    }

    // ---------------- 渲染 ----------------

    /**
     * 把命中的指标渲染成可以拼进提示词的一段文字。
     * 没命中时返回一句兜底说明（而不是空串），
     * 因为提示词里那段话被替换掉了，这里必须给出替代内容。
     */
    public String renderForPrompt(String question) {
        List<Metric> hit = match(question);
        if (hit.isEmpty()) {
            return EMPTY_BLOCK;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("【指标定义】（系统预定义的业务口径，命中时**必须直接使用**下面的表达式）\n");
        if (!table.isBlank()) {
            sb.append("以下指标都作用于表 ").append(table).append("。\n");
        }

        boolean hasCount = false;
        for (Metric metric : hit) {
            sb.append("· ").append(metric.name()).append("：聚合用 ").append(metric.expression());
            if (!metric.where().isBlank()) {
                sb.append("，并加筛选 ").append(metric.where());
            }
            if (!metric.description().isBlank()) {
                sb.append("\n    含义：").append(metric.description());
            }
            sb.append('\n');
            hasCount |= metric.expression().toUpperCase().contains("COUNT(");
        }

        sb.append("用法（务必遵守）：\n");
        sb.append("- 上面是**已经确定的口径**，直接采用，不要自己重新推导或替换写法\n");
        sb.append("- 指标自带的筛选条件必须与你自己的条件（时间、分类、账户等）用 AND 组合\n");
        if (hit.size() > 1) {
            sb.append("- 命中多个指标说明需要分别计算或分组对比（如「收入和支出各是多少」）\n");
        }
        if (hasCount) {
            sb.append("- 涉及「多少笔」的计数用 COUNT，不要用 SUM\n");
        }
        return sb.toString().stripTrailing();
    }

    // ---------------- 访问器 ----------------

    public List<Metric> all() {
        return metrics;
    }

    /** 「不适用」词表（诊断/预览用） */
    public List<String> skipWhen() {
        return skipWhen;
    }

    public boolean isEmpty() {
        return metrics.isEmpty();
    }

    public String table() {
        return table;
    }

    /** 加载情况说明（诊断用：配置到底加载成功了没有） */
    public String loadNote() {
        return loadNote;
    }
}
