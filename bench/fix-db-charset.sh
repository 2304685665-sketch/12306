#!/usr/bin/env bash
# 修复 Docker 初始化导入时中文被二次编码的问题（容器内 mysql 客户端按 latin1 读了 UTF-8 的 SQL 文件）
# 只在检测到乱码时执行；只修改「能按 latin1 原样往返」的非 ASCII 字段，正常 UTF-8 中文不会被改动
source "$(dirname "$0")/common.sh"

OK_HEX="E58C97E4BAAC"                              # 「北京」的正确 UTF-8
hex="$(sql "select upper(hex(name)) from t_region where code='BJP' limit 1")"
fixed_hex="$(sql "select upper(hex(CONVERT(BINARY CONVERT(name USING latin1) USING utf8mb4))) from t_region where code='BJP' limit 1")"
if [ "$hex" = "$OK_HEX" ]; then
  echo ">>> 中文编码正常（t_region.BJP = ${hex}），无需修复"; exit 0
elif [ "$fixed_hex" = "$OK_HEX" ]; then
  echo ">>> 检测到中文乱码（t_region.BJP = ${hex}，按 latin1 还原后 = ${fixed_hex}），开始修复"
else
  echo ">>> 无法判断编码（t_region.BJP = ${hex:-空}），不做修改"; exit 1
fi

backup="$HOME/12306-backup-before-charset-fix-$(date +%Y%m%d-%H%M%S).sql"
docker exec "$MYSQL_CONTAINER" mysqldump -uroot -proot --default-character-set=utf8mb4 12306 > "$backup" 2>/dev/null
echo ">>> 修复前备份：$backup"

# 一次生成全部 UPDATE，单个连接执行；每条后面输出「表.列<TAB>影响行数」
STATEMENTS="$(sql "select concat(
    'UPDATE \`', table_name, '\` SET \`', column_name, '\` = CONVERT(BINARY CONVERT(\`', column_name, '\` USING latin1) USING utf8mb4) ',
    'WHERE \`', column_name, '\` REGEXP ''[^ -~]'' AND BINARY CONVERT(CONVERT(\`', column_name, '\` USING latin1) USING utf8mb4) = BINARY \`', column_name, '\`; ',
    'SELECT ''', table_name, '.', column_name, ''', ROW_COUNT();')
  from information_schema.columns
  where table_schema='12306' and data_type in ('char','varchar','tinytext','text','mediumtext','longtext')")"
RESULT="$(echo "$STATEMENTS" | docker exec -i "$MYSQL_CONTAINER" mysql -uroot -proot --default-character-set=utf8mb4 -N -B 12306 2>&1 | grep -v 'Using a password' || true)"
if grep '^ERROR' <<< "$RESULT" >/dev/null; then echo "$RESULT" | grep '^ERROR' | head -5; echo "修复出错，可用备份还原：${backup}"; exit 1; fi
echo "$RESULT" | awk -F'\t' '$2 > 0 {printf "    %s：%s 行\n", $1, $2}'
total="$(echo "$RESULT" | awk -F'\t' '{s += $2} END {print s + 0}')"

hex="$(sql "select upper(hex(name)) from t_region where code='BJP' limit 1")"
echo ">>> 共修复 $total 个字段值，t_region.BJP = ${hex}（$(sql "select name from t_region where code='BJP'")）"
[ "$hex" = "$OK_HEX" ] || { echo "修复后仍不正确，可用备份还原：$backup"; exit 1; }

# Redis 里缓存的是乱码数据，一并清掉（应用会按需重建）
for p in "index12306-ticket-service:*" "index12306-user-service:*"; do redis_del_pattern "$p"; done
echo ">>> 已清理 ticket / user 服务的 Redis 缓存"
