#!/usr/bin/env bash
# ============================================================
# 财务问数 Agent · 开发辅助脚本
# 运行环境：Git Bash（不要用 PowerShell / CMD）
#
# 用法：
#   bash dev.sh db-init              初始化数据库（建表 + 导入数据 + 建只读账号）
#   bash dev.sh db-check             查看数据库当前状态
#   bash dev.sh compile              编译
#   bash dev.sh run Step2Main        编译并运行指定主类
#   bash dev.sh run Step4Main "上个月支出多少"    运行并传参（参数会转发给主类）
#   bash dev.sh run Step8Main L7     只跑 L7 挑战题（参数同样会转发）
#   bash dev.sh sql "SELECT ..."     用只读账号执行一条 SQL
#   bash dev.sh env                 查看当前环境（排查乱码/缺少 Key 用）
# ============================================================
set -euo pipefail
cd "$(dirname "$0")"

# ============================================================
# 【重要】控制台代码页固定为 936(GBK)，不要改成 65001
#
# 实测对照（2026-09-29）：
#   65001 → 输出正常，但中文【输入】被破坏：7 个汉字只剩 7 个字节（0x50/0xA0/0x90），
#           信息在终端层就丢了，程序侧无论怎么解码都救不回来。
#   936   → 输入正常（14 字节正确 GBK），输出也正常。
#
# 根因：中文 Windows 的原生代码页是 936，conhost 在 65001 下处理 CJK 输入存在缺陷。
#
# 正确做法是【两端一致】：控制台 936 + Java 用平台默认编码(GBK)。
# 所以下面 run 分支不再传 -Dsun.stdout.encoding，让 Java 跟随控制台编码。
# 强制设 936 还有一个好处：已经开着的老窗口（可能残留在 65001）也会被纠正。
# ============================================================
chcp.com 936 >/dev/null 2>&1 || true

# ============================================================
# 【凭据】真实密码不写在本文件里 —— 本仓库是公开的
# ------------------------------------------------------------
# 来源优先级：环境变量  >  dev.local.sh（本地文件，已在 .gitignore 中）
#
#   推荐做法：把 dev.local.sh.example 复制成 dev.local.sh，填入你的真实密码。
#   这样你的本地命令完全不受影响，而仓库里不会出现任何明文密码。
#
# 只有真正要连库的命令（db-init / db-check / sql）才校验凭据，
# 所以 compile / run / env 在没有 dev.local.sh 时也能正常使用。
# ============================================================
if [ -f dev.local.sh ]; then
  # shellcheck disable=SC1091
  . ./dev.local.sh
fi

# 需要连库的命令，缺凭据时给出可执行的提示，而不是抛一个看不懂的 mysql 报错
require_db_creds() {
  if [ -z "${FINANCE_DB_ROOT_PASSWORD:-}" ]; then
    say "× 未配置 MySQL root 密码（FINANCE_DB_ROOT_PASSWORD）。"
    say "  做法一：cp dev.local.sh.example dev.local.sh，然后填入你的密码"
    say "  做法二：export FINANCE_DB_ROOT_PASSWORD='你的密码'"
    exit 1
  fi
  if [ -z "${FINANCE_DB_PASSWORD:-}" ]; then
    say "× 未配置只读账号密码（FINANCE_DB_PASSWORD）。"
    say "  做法一：cp dev.local.sh.example dev.local.sh，然后填入你的密码"
    say "  做法二：export FINANCE_DB_PASSWORD='你的密码'"
    exit 1
  fi
}

MYSQL_BIN="${MYSQL_BIN:-D:/project/mysql/bin/mysql.exe}"
DB="personal_finance"
CHARSET="--default-character-set=utf8mb4"
ROOT_AUTH=(-u "${FINANCE_DB_ROOT_USER:-root}" -p"${FINANCE_DB_ROOT_PASSWORD:-}")
READONLY_AUTH=(-u "${FINANCE_DB_USER:-ai_readonly}" -p"${FINANCE_DB_PASSWORD:-}")
CP_FILE="target/cp.txt"
PKG="com.jiang.financeagent"

# 密码写在命令行会触发 mysql 的 Warning，属正常提示；这里统一丢弃，避免刷屏。
# 注意：只丢 stderr，SQL 执行结果仍在 stdout 正常显示。
q() { "$MYSQL_BIN" "$@" 2>/dev/null; }

# ============================================================
# 输出编码统一（2026-10-04 修复）
# ------------------------------------------------------------
# 实测：控制台代码页是 936(GBK)，但三条输出路径的编码并不一致 ——
#     ① Java 程序输出    → GBK（用平台默认编码，已对齐）
#     ② mysql 客户端输出 → UTF-8
#     ③ 本脚本自身 echo  → UTF-8（脚本文件是 UTF-8）
# 后果：①显示正常，②③乱码。
#
# 修复：把②③在「展示」这一侧转成 GBK。
#   ★ 不动 mysql 的 --default-character-set：它同时决定「读 SQL 文件」用的编码。
#     sql/*.sql 是 UTF-8，改成 gbk 会把建表脚本里的中文注释写坏（输入侧必须保持 utf8mb4）。
#   ★ 只转输出、不转输入 —— 和 Java 那次「改输出把输入搞坏」的教训是同一个道理：
#     先想清楚改动的方向，再动手。
# ============================================================
to_console() { iconv -f UTF-8 -t GBK -c 2>/dev/null || true; }
say() { echo "$@" | to_console; }
qout() { "$MYSQL_BIN" "$@" 2>/dev/null | to_console; }

if [ ! -f "$MYSQL_BIN" ]; then
  say "× 找不到 mysql 客户端：$MYSQL_BIN"
  say "  如果你的 MySQL 装在别处，请先执行：export MYSQL_BIN='你的 mysql.exe 路径'"
  exit 1
fi

# 只有连库的命令需要凭据；其余命令（compile / run / env）不校验
case "${1:-}" in
  db-init|db-check|sql) require_db_creds ;;
esac

case "${1:-}" in

  db-init)
    say "===== 1/4 建库建表 ====="
    q "${ROOT_AUTH[@]}" $CHARSET -e "source sql/01_schema.sql"
    say "===== 2/4 导入你的原始数据 ====="
    q "${ROOT_AUTH[@]}" $CHARSET "$DB" -e "source sql/02_your_original_data.sql"
    say "===== 3/4 补充近三个月数据 ====="
    q "${ROOT_AUTH[@]}" $CHARSET -e "source sql/03_extra_months.sql"
    say "===== 4/4 创建只读账号 ====="
    # 把 SQL 里的占位符替换成 dev.local.sh 中的真实密码，
    # 这样用户只需要在一个地方维护密码（体验上不用改两个文件）。
    # 用 | 作分隔符并转义特殊字符，避免密码里含 & / \ 时 sed 出错。
    ESC_PWD=$(printf '%s' "$FINANCE_DB_PASSWORD" | sed 's/[&|\\]/\\&/g')
    sed "s|CHANGE_ME_readonly_password|$ESC_PWD|g" sql/04_readonly_user.sql > .tmp_readonly.sql
    q "${ROOT_AUTH[@]}" $CHARSET -e "source .tmp_readonly.sql"
    rm -f .tmp_readonly.sql
    echo
    say "初始化完成。"
    ;;

  db-check)
    qout "${ROOT_AUTH[@]}" $CHARSET "$DB" -e "
      SELECT '表数量' AS 项, COUNT(*) AS 值
        FROM information_schema.tables WHERE table_schema='$DB'
      UNION ALL SELECT '交易记录数', COUNT(*) FROM t_transaction
      UNION ALL SELECT '账户数',     COUNT(*) FROM t_account
      UNION ALL SELECT '分类数',     COUNT(*) FROM t_category;"
    ;;

  compile)
    mvn -B -q clean compile
    say "编译完成：target/classes"
    ;;

  run)
    MAIN="${2:?用法: bash dev.sh run <主类名> [传给程序的参数...]}"
    mvn -B -q clean compile
    mvn -B -q dependency:build-classpath "-Dmdep.outputFile=$CP_FILE"
    CP="target/classes;$(tr -d '\r\n' < "$CP_FILE")"
    # 类名不含包名时自动补上默认包
    case "$MAIN" in
      *.*) FQCN="$MAIN" ;;
      *)   FQCN="$PKG.$MAIN" ;;
    esac
    if [ -z "${DEEPSEEK_API_KEY:-}" ]; then
      say "提示：未检测到 DEEPSEEK_API_KEY（第 4 步起才需要，届时请重开 Git Bash）"
    fi
    # 这里刻意不传 -Dsun.stdout.encoding：
    # 让 Java 使用平台默认编码(GBK)，与上面固定的控制台代码页 936 保持一致。
    #
    # ★ 把第 3 个及之后的参数原样转发给 Java 主类。
    #   之前这里漏了 "${@:3}"，导致下面两条命令都"看起来跑了、其实没生效"：
    #     bash dev.sh run Step8Main L7            → L7 被丢掉，跑的是全部 26 条
    #     bash dev.sh run Step4Main "上个月支出"    → 参数被丢掉，静默进入交互模式
    #   这类 bug 最恶心：不报错、有输出，只是行为和预期不一样。
    #   判断方法是看输出里有没有本该由参数决定的东西（比如"用例数：6"而不是 26）。
    if [ "$#" -gt 2 ]; then
      java -cp "$CP" "$FQCN" "${@:3}"
    else
      java -cp "$CP" "$FQCN"
    fi
    ;;

  sql)
    STMT="${2:?用法: bash dev.sh sql \"SELECT ...\"}"
    qout "${READONLY_AUTH[@]}" $CHARSET "$DB" -e "$STMT"
    ;;

  env)
    say "Shell        : ${SHELL:-（不是 Bash，请改用 Git Bash）}"
    say "Bash 版本    : ${BASH_VERSION:-（空 = 当前不是 Bash 环境）}"
    # chcp.com 的输出本身就是控制台编码(GBK)，不能再过 to_console（否则中文被二次转换丢掉）。
    # 前缀自己也要转成 GBK，否则一行里两种编码并存 → 前缀乱码。
    echo -n "控制台代码页 : " | to_console
    chcp.com 2>/dev/null | tr -d '\r'
    say "JAVA_HOME    : ${JAVA_HOME:-（未设置）}"
    if [ -n "${DEEPSEEK_API_KEY:-}" ]; then
      say "DEEPSEEK_API_KEY : 已设置（长度 ${#DEEPSEEK_API_KEY}）"
    else
      say "DEEPSEEK_API_KEY : 未设置（第 4 步起才需要）"
    fi
    ;;

  *)
    sed -n '3,13p' "$0" | sed 's/^# \{0,1\}//' | to_console
    exit 1
    ;;
esac
