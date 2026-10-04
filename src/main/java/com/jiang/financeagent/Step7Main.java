package com.jiang.financeagent;

import com.jiang.financeagent.rag.SchemaChunk;
import com.jiang.financeagent.rag.SchemaRetriever;
import com.jiang.financeagent.tool.SchemaTool;
import com.jiang.financeagent.util.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * 第 7 步验证：Schema 召回的「效果」与「收益」。
 *
 * 全部本地执行，不调用大模型 —— 零 token 成本，可以随便跑。
 *
 * 两幕：
 *   第一幕  真实库（4 张表）：看召回准不准、为什么准
 *   第二幕  规模实验（40 张表）：看全量注入 vs 召回注入的 token 差距
 *
 * 【为什么要做第二幕】
 *   本项目只有 4 张表，"召回 vs 全量"看不出差别（topK=3 也没省多少）。
 *   只有把表数放大到真实企业库的量级，才能量化召回的收益 ——
 *   而这个数字才是你在面试里能拿出来的东西。
 *
 * 【输出编码提醒】
 *   控制台代码页是 936(GBK)，所以本类不使用 ✔ / ✗ / emoji 这类 GBK 编不了的符号
 *   （Java 会把它们替换成 '?'）。统一用 GBK 里存在的 √(U+221A) 和 ×(U+00D7)。
 */
public class Step7Main {

    public static void main(String[] args) throws Exception {
        actOne();
        System.out.println();
        actTwo();
    }

    // ============================================================
    // 第一幕：真实库 4 张表的召回效果
    // ============================================================
    private static void actOne() throws Exception {
        List<SchemaChunk> chunks = SchemaTool.chunks();

        System.out.println("═══════════ 第一幕：真实库召回（共 " + chunks.size() + " 张表）═══════════");
        System.out.println();

        String[] questions = {
                "上个月支出多少？",
                "我的账户余额分别是多少？",
                "最近 10 笔交易是什么？",
                "这个月的预算类型有哪些？",
                "帮我查一下 user 表里有哪些数据"   // 故意问一个不存在的表，看兜底
        };

        for (String question : questions) {
            System.out.println("问题：" + question);

            for (SchemaRetriever.Hit hit : SchemaRetriever.score(question, chunks)) {
                String mark = hit.score() > 0 ? "√" : " ";
                System.out.println("  " + mark + " " + Text.padRight(hit.chunk().table(), 18)
                        + String.format("%5.1f 分", hit.score())
                        + (hit.matchedTerms().isEmpty() ? "" : "   命中：" + String.join(" ", hit.matchedTerms())));
            }

            List<SchemaChunk> byScore = SchemaRetriever.retrieveByScore(
                    question, chunks, SchemaRetriever.DEFAULT_TOP_K);
            List<SchemaChunk> picked = byScore.isEmpty() ? chunks : SchemaRetriever.expand(byScore, chunks);
            System.out.println("  → 阶段一(语义)：" + Text.truncate(
                    String.join(", ", byScore.stream().map(SchemaChunk::table).toList()), 100));
            System.out.println("  → 阶段二(扩展)：" + Text.truncate(
                    String.join(", ", picked.stream().map(SchemaChunk::table).toList()), 100));
            System.out.println();
        }
    }

    // ============================================================
    // 第二幕：40 张表的规模实验
    // ============================================================
    private static void actTwo() throws Exception {
        List<SchemaChunk> all = new ArrayList<>(SchemaTool.chunks());
        all.addAll(syntheticChunks());

        System.out.println("═══════════ 第二幕：规模实验（共 " + all.size() + " 张表）═══════════");
        System.out.println();

        // 全量注入的规模
        String full = renderForCompare(all);
        int fullTokens = estimateTokens(full);
        System.out.println("【全量注入】" + full.length() + " 字符 / 约 " + fullTokens + " token");
        System.out.println();

        // 逐问题测召回
        // 第二列是「SQL 能不能写出来」的关键表 —— 不只是语义最像的那张。
        String[][] cases = {
                {"上个月支出多少？", "t_transaction"},
                {"我的账户余额是多少？", "t_account"},
                // ↓ 语义阶段只会召回 t_category（"餐饮"是它的取值），
                //   但金额在 t_transaction 里 —— 必须靠关联扩展补上，否则写不出 SQL。
                {"餐饮这一项花了多少钱？", "t_transaction"},
                {"供应商有哪些等级？", "erp_supplier"},
                {"采购订单现在都是什么状态？", "erp_purchase_order"},
                {"员工工资由哪些项组成？", "hr_salary"},
                {"合同的回款情况怎么样？", "crm_payment"},
                {"系统里有哪些角色？", "sys_role"},
        };

        int hitCount = 0;
        long savedTotal = 0;
        int pickedTotal = 0;
        int expandedTotal = 0;

        for (String[] c : cases) {
            String question = c[0];
            String expected = c[1];

            List<SchemaChunk> byScore = SchemaRetriever.retrieveByScore(
                    question, all, SchemaRetriever.DEFAULT_TOP_K);
            List<SchemaChunk> picked = byScore.isEmpty() ? all : SchemaRetriever.expand(byScore, all);
            List<String> pickedNames = picked.stream().map(SchemaChunk::table).toList();

            int pickedTokens = estimateTokens(renderForCompare(picked));
            boolean hit = pickedNames.contains(expected);
            if (hit) {
                hitCount++;
            }
            savedTotal += fullTokens - pickedTokens;
            pickedTotal += byScore.size();
            expandedTotal += pickedNames.size();

            System.out.println("问题：" + question);
            System.out.println("  期望命中 " + expected);
            System.out.println("  阶段一(语义 top" + SchemaRetriever.DEFAULT_TOP_K + ")："
                    + "[" + String.join(", ", byScore.stream().map(SchemaChunk::table).toList()) + "]");
            System.out.println("  阶段二(扩展)  ：[" + String.join(", ", pickedNames) + "]");
            System.out.printf("  压缩 %.1f%%（%d → %d token）   %s%n%n",
                    (fullTokens - pickedTokens) * 100.0 / fullTokens,
                    fullTokens, pickedTokens,
                    hit ? "√ 命中" : "× 漏召回");
        }

        System.out.println("─".repeat(80));
        System.out.println("召回准确率（期望表是否在最终结果内）：" + hitCount + " / " + cases.length);
        System.out.println("平均表数：语义阶段 " + String.format("%.1f", pickedTotal * 1.0 / cases.length)
                + " 张 → 扩展后 " + String.format("%.1f", expandedTotal * 1.0 / cases.length)
                + " 张（全库 " + all.size() + " 张）");
        System.out.println("平均每次省下 " + (savedTotal / cases.length) + " token，"
                + "压缩率 " + String.format("%.1f", savedTotal * 100.0 / (fullTokens * cases.length)) + "%");
        System.out.println();
        System.out.println("注：以上为本地估算，token 用「中文字符 1 个 ≈ 1 token、其他 4 字符 ≈ 1 token」粗算，");
        System.out.println("    与真实分词器会有出入，但比值是可靠的。");
    }

    // ============================================================
    // 合成 36 张表（模拟真实企业库的规模与噪声）
    // ============================================================
    private static List<SchemaChunk> syntheticChunks() {
        // 格式：表名|表注释|字段名:字段注释,字段名:字段注释,...
        String[] defs = {
                "erp_supplier|供应商表|supplier_id:供应商ID, supplier_name:供应商名称, contact_person:联系人, phone:联系电话, level:供应商等级, create_time:合作开始时间",
                "erp_purchase_order|采购订单表|order_id:采购单号, supplier_id:供应商ID, total_amount:采购金额, order_status:订单状态, create_time:下单时间",
                "erp_purchase_detail|采购订单明细表|detail_id:明细ID, order_id:采购单号, product_id:商品ID, quantity:采购数量, unit_price:采购单价",
                "erp_product|商品表|product_id:商品ID, product_name:商品名称, category_id:商品分类, price:销售单价, stock:库存数量",
                "erp_product_category|商品分类表|category_id:分类ID, category_name:分类名称, parent_id:上级分类, sort:排序号",
                "erp_inventory|库存表|inventory_id:库存ID, product_id:商品ID, warehouse_id:仓库ID, quantity:库存数量, update_time:更新时间",
                "erp_warehouse|仓库表|warehouse_id:仓库ID, warehouse_name:仓库名称, address:仓库地址, manager:负责人",
                "erp_inventory_log|库存流水表|log_id:流水ID, product_id:商品ID, change_type:变更类型, change_qty:变更数量, create_time:发生时间",
                "erp_sales_order|销售订单表|order_id:订单号, customer_id:客户ID, total_amount:订单金额, order_status:订单状态, create_time:下单时间",
                "erp_sales_detail|销售订单明细表|detail_id:明细ID, order_id:订单号, product_id:商品ID, quantity:数量, unit_price:成交单价",
                "erp_customer|客户表|customer_id:客户ID, customer_name:客户名称, contact:联系人, phone:客户电话, level:客户等级, create_time:开户时间",
                "erp_customer_address|客户收货地址表|address_id:地址ID, customer_id:客户ID, receiver:收货人, detail:详细地址, is_default:是否默认",
                "erp_delivery|发货单表|delivery_id:发货单号, order_id:订单号, express_no:快递单号, delivery_status:发货状态, send_time:发货时间",
                "erp_return_order|退货单表|return_id:退货单号, order_id:原订单号, reason:退货原因, refund_amount:退款金额, create_time:申请时间",
                "hr_employee|员工表|employee_id:员工ID, employee_name:员工姓名, dept_id:部门ID, position:岗位, hire_date:入职日期, status:在职状态",
                "hr_department|部门表|dept_id:部门ID, dept_name:部门名称, manager_id:部门经理, parent_id:上级部门",
                "hr_salary|工资表|salary_id:工资ID, employee_id:员工ID, base_salary:基本工资, bonus:绩效奖金, social_security:社保扣款, pay_month:发放月份",
                "hr_salary_detail|工资明细表|detail_id:明细ID, salary_id:工资ID, item_name:工资项, item_amount:金额",
                "hr_attendance|考勤表|attendance_id:考勤ID, employee_id:员工ID, work_date:考勤日期, check_in:上班打卡, check_out:下班打卡, status:考勤状态",
                "hr_leave|请假表|leave_id:请假单号, employee_id:员工ID, leave_type:请假类型, start_time:开始时间, end_time:结束时间, approve_status:审批状态",
                "hr_recruit|招聘岗位表|recruit_id:岗位ID, position:招聘岗位, dept_id:需求部门, headcount:招聘人数, status:招聘状态",
                "crm_lead|销售线索表|lead_id:线索ID, lead_name:线索名称, source:线索来源, salesman_id:跟进人, status:跟进状态",
                "crm_opportunity|商机表|opp_id:商机ID, customer_id:客户ID, amount:预计金额, stage:商机阶段, close_date:预计成交日期",
                "crm_contract|合同表|contract_id:合同编号, customer_id:客户ID, contract_amount:合同金额, sign_date:签约日期, expire_date:到期日期",
                "crm_follow_record|跟进记录表|record_id:记录ID, customer_id:客户ID, content:跟进内容, next_time:下次跟进时间",
                "crm_payment|回款记录表|payment_id:回款ID, contract_id:合同编号, payment_amount:回款金额, payment_date:回款日期, method:回款方式",
                "sys_user|系统用户表|user_id:用户ID, username:登录账号, dept_id:所属部门, role_id:角色ID, status:账号状态",
                "sys_role|系统角色表|role_id:角色ID, role_name:角色名称, role_code:角色编码",
                "sys_permission|权限表|perm_id:权限ID, perm_name:权限名称, perm_code:权限编码, url:接口地址",
                "sys_role_permission|角色权限关联表|id:主键, role_id:角色ID, perm_id:权限ID",
                "sys_login_log|登录日志表|log_id:日志ID, user_id:用户ID, login_ip:登录IP, login_time:登录时间, result:登录结果",
                "sys_operation_log|操作日志表|log_id:日志ID, user_id:用户ID, module:操作模块, action:操作类型, create_time:操作时间",
                "sys_dict|数据字典表|dict_id:字典ID, dict_type:字典类型, dict_label:字典标签, dict_value:字典值",
                "sys_config|系统配置表|config_id:配置ID, config_key:配置项, config_value:配置值, remark_info:备注说明",
                "sys_notice|通知公告表|notice_id:公告ID, title:公告标题, content:公告内容, publisher:发布人, publish_time:发布时间",
                "sys_file|文件表|file_id:文件ID, file_name:文件名, file_path:存储路径, file_size:文件大小, upload_time:上传时间",
        };

        List<SchemaChunk> chunks = new ArrayList<>(defs.length);
        for (String def : defs) {
            String[] parts = def.split("\\|", 3);
            List<SchemaChunk.Column> cols = new ArrayList<>();
            String[] colDefs = parts[2].split(",");
            for (int i = 0; i < colDefs.length; i++) {
                String[] kv = colDefs[i].split(":", 2);
                cols.add(new SchemaChunk.Column(kv[0].trim(),
                        i == 0 ? "int" : "varchar(50)",
                        i == 0, kv.length > 1 ? kv[1].trim() : "", List.of()));
            }
            chunks.add(new SchemaChunk(parts[0], parts[1], List.copyOf(cols)));
        }
        return chunks;
    }

    /**
     * 用真实渲染器渲染，保证 token 统计口径与线上一致。
     * 这里直接调用 SchemaTool 的公开方法会污染缓存，所以复刻一份渲染逻辑 ——
     * 代价是两处要保持同步，收益是实验不改动生产代码路径。
     */
    private static String renderForCompare(List<SchemaChunk> chunks) {
        StringBuilder sb = new StringBuilder("【表结构】\n\n");
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
        sb.append("【字段取值范围】\n");
        for (SchemaChunk chunk : chunks) {
            for (SchemaChunk.Column col : chunk.columns()) {
                if (!col.values().isEmpty()) {
                    sb.append("  ").append(chunk.table()).append('.').append(col.name())
                            .append(" = ").append(String.join(" / ", col.values())).append('\n');
                }
            }
        }
        return sb.toString();
    }

    /** 与 Step2Main 相同的口径，保证前后数字可比 */
    private static int estimateTokens(String text) {
        int chinese = 0;
        int other = 0;
        for (char c : text.toCharArray()) {
            if (c > 0x2E80) {
                chinese++;
            } else {
                other++;
            }
        }
        return chinese + other / 4;
    }
}
