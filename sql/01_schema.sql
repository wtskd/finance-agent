-- ============================================================
-- 财务问数 Agent · 第 1 步：建库建表
-- 数据来源：江锦隆《基于 Spring Cloud 的个人财务管理系统》
-- 说明：原项目导出脚本只含 INSERT、缺 DDL，此文件为按数据字典 + 实际数据反推重建
-- 字符集：utf8mb4（原数据含中文状态值「收入/支出/正常/禁用」，必须是 utf8mb4）
-- ============================================================

CREATE DATABASE IF NOT EXISTS personal_finance
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_general_ci;

USE personal_finance;

-- 按外键依赖倒序删除，保证脚本可重复执行
DROP TABLE IF EXISTS t_transaction;
DROP TABLE IF EXISTS t_category;
DROP TABLE IF EXISTS t_account;
DROP TABLE IF EXISTS t_budget_type;
DROP TABLE IF EXISTS `user`;
DROP TABLE IF EXISTS `role`;

-- ---------- 1. 角色表 ----------
CREATE TABLE `role` (
    rid      INT         NOT NULL AUTO_INCREMENT COMMENT '角色ID',
    roleName VARCHAR(50) NOT NULL                COMMENT '角色名称',
    PRIMARY KEY (rid)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '角色表';

-- ---------- 2. 用户表 ----------
CREATE TABLE `user` (
    uid        INT          NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    name       VARCHAR(100) NOT NULL                COMMENT '用户名',
    password   VARCHAR(100) NOT NULL                COMMENT '密码',
    telephone  BIGINT       DEFAULT NULL            COMMENT '电话',
    createTime DATETIME     DEFAULT NULL            COMMENT '创建时间',
    role_id    INT          DEFAULT NULL            COMMENT '角色ID',
    PRIMARY KEY (uid),
    KEY idx_user_role (role_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '用户表';

-- ---------- 3. 预算类型表 ----------
CREATE TABLE t_budget_type (
    type_id   INT          NOT NULL AUTO_INCREMENT COMMENT '类型ID',
    type_name VARCHAR(100) NOT NULL                COMMENT '类型名称',
    PRIMARY KEY (type_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '预算类型表';

-- ---------- 4. 财务账户表 ----------
CREATE TABLE t_account (
    account_id   INT           NOT NULL AUTO_INCREMENT COMMENT '账户ID',
    account_name VARCHAR(100)  NOT NULL                COMMENT '账户名称',
    balance      DECIMAL(10,2) NOT NULL DEFAULT 0.00   COMMENT '账户余额，信用卡可为负',
    create_time  DATETIME      DEFAULT NULL            COMMENT '创建时间',
    status       VARCHAR(10)   NOT NULL DEFAULT '正常' COMMENT '状态：正常/禁用',
    type_id      INT           DEFAULT NULL            COMMENT '预算类型ID',
    PRIMARY KEY (account_id),
    KEY idx_account_type (type_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '财务账户表';

-- ---------- 5. 收支分类表 ----------
-- 注意：type_id 1=收入 2=支出；amount 是该分类的预算金额
CREATE TABLE t_category (
    category_id   INT           NOT NULL AUTO_INCREMENT COMMENT '分类ID',
    category_name VARCHAR(100)  NOT NULL                COMMENT '分类名称',
    amount        DECIMAL(10,2) NOT NULL DEFAULT 0.00   COMMENT '该分类预算金额',
    icon          VARCHAR(255)  DEFAULT NULL            COMMENT '图标路径',
    description   VARCHAR(255)  DEFAULT NULL            COMMENT '描述',
    type_id       INT           NOT NULL                COMMENT '类型：1=收入 2=支出',
    PRIMARY KEY (category_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '收支分类表';

-- ---------- 6. 交易记录表（核心表）----------
-- 注意：status 存的是中文「收入/支出」而不是编码，这是原系统的真实设计，
--       也是后面 text2SQL 最容易出错的地方之一，先记下来
CREATE TABLE t_transaction (
    transaction_id INT           NOT NULL AUTO_INCREMENT COMMENT '交易ID',
    amount         DECIMAL(10,2) NOT NULL                COMMENT '金额（正数）',
    account_id     INT           DEFAULT NULL            COMMENT '账户ID',
    create_time    DATETIME      DEFAULT NULL            COMMENT '交易时间',
    status         VARCHAR(10)   NOT NULL                COMMENT '类型：收入/支出',
    category_id    INT           DEFAULT NULL            COMMENT '分类ID',
    PRIMARY KEY (transaction_id),
    KEY idx_tx_account (account_id),
    KEY idx_tx_category (category_id),
    KEY idx_tx_time (create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '交易记录表';

SELECT '建表完成' AS step, COUNT(*) AS table_count
FROM information_schema.tables
WHERE table_schema = 'personal_finance';
