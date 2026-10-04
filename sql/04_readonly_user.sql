-- ============================================================
-- 财务问数 Agent · 第 1 步（续）：创建「只读」数据库账号
--
-- 【为什么必须单独建只读账号】
--   Agent 会把大模型生成的 SQL 直接丢给数据库执行。
--   大模型是会犯错的 —— 它可能生成 UPDATE、DELETE、DROP，
--   也可能被用户的提示注入（prompt injection）诱导去删表。
--
--   只读账号是「最后一道物理防线」：
--   即使前面所有校验全部失效、模型真的生成了 DELETE，
--   数据库也会直接拒绝执行。这是权限最小化原则的落地。
--
--   面试时这一点值得主动讲 —— 它区分了"玩具 Demo"和"工程实现"。
-- ============================================================

-- 只读账号：仅本机可连，仅对本库有 SELECT 权限
-- ⚠️ 下面的密码是占位符。请替换成你自己的密码，
--    并同步写入本地文件 dev.local.sh（该文件在 .gitignore 中，不会被提交）。
CREATE USER IF NOT EXISTS 'ai_readonly'@'localhost' IDENTIFIED BY 'CHANGE_ME_readonly_password';

-- 先收回所有权限（保证可重复执行时状态干净）
REVOKE ALL PRIVILEGES, GRANT OPTION FROM 'ai_readonly'@'localhost';

-- 只给 SELECT，不给 INSERT / UPDATE / DELETE / DROP / ALTER
GRANT SELECT ON personal_finance.* TO 'ai_readonly'@'localhost';

FLUSH PRIVILEGES;

-- ---------- 验证：应该只能看到 SELECT ----------
SELECT '当前授予的权限' AS 说明;
SHOW GRANTS FOR 'ai_readonly'@'localhost';
