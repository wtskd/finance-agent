-- ============================================================
-- 财务问数 Agent · 第 1 步（续）：补充最近 3 个月数据
--
-- 【为什么要补】
--   你的原始数据覆盖 2026-01 ~ 2026-06。今天是 2026-09-29，
--   若不补充，用户问「上个月支出多少」时数据库里根本没有 8 月数据，
--   Agent 只能回答 0 —— 这不是 Agent 的错，是数据的问题。
--   一个连演示都跑不通的数据集，会让整个项目显得不可信。
--
-- 【补充原则】
--   1. 完全沿用自己的记账习惯（每月 10 日工资、20 日房租、餐饮高频）
--   2. 交易ID 从 101 起，避开原数据已占用的 1-38、44-46
--   3. 9 月为部分数据（截至 9/27），符合"今天还没过完"的真实情况
-- ============================================================

USE personal_finance;

-- ---------- 2026 年 7 月 ----------
INSERT INTO t_transaction VALUES (101, 8000.00, 1, '2026-07-10 09:00:00', '收入', 7);
INSERT INTO t_transaction VALUES (102, 1500.00, 1, '2026-07-20 10:00:00', '支出', 3);
INSERT INTO t_transaction VALUES (103, 268.00, 2, '2026-07-05 12:30:00', '支出', 1);
INSERT INTO t_transaction VALUES (104, 180.00, 2, '2026-07-14 19:00:00', '支出', 1);
INSERT INTO t_transaction VALUES (105, 320.00, 2, '2026-07-22 12:00:00', '支出', 1);
INSERT INTO t_transaction VALUES (106, 150.00, 3, '2026-07-08 08:00:00', '支出', 2);
INSERT INTO t_transaction VALUES (107, 200.00, 2, '2026-07-16 18:00:00', '支出', 2);
INSERT INTO t_transaction VALUES (108, 880.00, 2, '2026-07-11 15:00:00', '支出', 4);
INSERT INTO t_transaction VALUES (109, 260.00, 2, '2026-07-19 20:30:00', '支出', 5);
INSERT INTO t_transaction VALUES (110, 520.00, 2, '2026-07-26 14:00:00', '支出', 4);
INSERT INTO t_transaction VALUES (111, 300.00, 2, '2026-07-28 10:00:00', '收入', 8);
INSERT INTO t_transaction VALUES (112, 800.00, 2, '2026-07-30 20:00:00', '收入', 9);
INSERT INTO t_transaction VALUES (113, 460.00, 2, '2026-07-25 19:00:00', '支出', 5);

-- ---------- 2026 年 8 月 ----------
INSERT INTO t_transaction VALUES (114, 8000.00, 1, '2026-08-10 09:00:00', '收入', 7);
INSERT INTO t_transaction VALUES (115, 1500.00, 1, '2026-08-20 10:00:00', '支出', 3);
INSERT INTO t_transaction VALUES (116, 310.00, 2, '2026-08-04 12:00:00', '支出', 1);
INSERT INTO t_transaction VALUES (117, 245.00, 2, '2026-08-13 19:30:00', '支出', 1);
INSERT INTO t_transaction VALUES (118, 190.00, 2, '2026-08-21 12:30:00', '支出', 1);
INSERT INTO t_transaction VALUES (119, 130.00, 3, '2026-08-07 08:30:00', '支出', 2);
INSERT INTO t_transaction VALUES (120, 1250.00, 2, '2026-08-15 16:00:00', '支出', 4);
INSERT INTO t_transaction VALUES (121, 680.00, 1, '2026-08-18 20:00:00', '支出', 5);
INSERT INTO t_transaction VALUES (122, 320.00, 2, '2026-08-24 11:00:00', '支出', 6);
INSERT INTO t_transaction VALUES (123, 168.00, 2, '2026-08-27 18:00:00', '支出', 2);
INSERT INTO t_transaction VALUES (124, 1500.00, 2, '2026-08-25 15:00:00', '收入', 9);
INSERT INTO t_transaction VALUES (125, 420.00, 2, '2026-08-28 20:00:00', '支出', 5);
INSERT INTO t_transaction VALUES (126, 2100.00, 4, '2026-08-30 10:00:00', '支出', 4);

-- ---------- 2026 年 9 月（部分，截至 9/27）----------
INSERT INTO t_transaction VALUES (127, 8000.00, 1, '2026-09-10 09:00:00', '收入', 7);
INSERT INTO t_transaction VALUES (128, 1500.00, 1, '2026-09-20 10:00:00', '支出', 3);
INSERT INTO t_transaction VALUES (129, 289.00, 2, '2026-09-03 12:00:00', '支出', 1);
INSERT INTO t_transaction VALUES (130, 356.00, 2, '2026-09-12 19:00:00', '支出', 1);
INSERT INTO t_transaction VALUES (131, 178.00, 2, '2026-09-19 12:30:00', '支出', 1);
INSERT INTO t_transaction VALUES (132, 240.00, 2, '2026-09-24 08:00:00', '支出', 2);
INSERT INTO t_transaction VALUES (133, 450.00, 2, '2026-09-08 10:00:00', '支出', 6);
INSERT INTO t_transaction VALUES (134, 1980.00, 4, '2026-09-15 16:00:00', '支出', 4);
INSERT INTO t_transaction VALUES (135, 320.00, 2, '2026-09-21 20:00:00', '支出', 5);
INSERT INTO t_transaction VALUES (136, 250.00, 2, '2026-09-22 09:00:00', '收入', 8);
INSERT INTO t_transaction VALUES (137, 160.00, 3, '2026-09-06 08:30:00', '支出', 1);
INSERT INTO t_transaction VALUES (138, 680.00, 2, '2026-09-26 19:30:00', '支出', 5);
INSERT INTO t_transaction VALUES (139, 900.00, 1, '2026-09-27 14:00:00', '支出', 5);

-- ---------- 校验 ----------
SELECT '交易总数' AS 指标, COUNT(*) AS 值 FROM t_transaction
UNION ALL
SELECT '最早交易', MIN(DATE(create_time)) FROM t_transaction
UNION ALL
SELECT '最晚交易', MAX(DATE(create_time)) FROM t_transaction;
