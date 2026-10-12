#!/usr/bin/env bash
# 压测公共配置：docker-compose 里的中间件 + 本机直接跑的 aggregation-service
set -euo pipefail

BENCH_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$BENCH_DIR/.." && pwd)"
RESULT_DIR="$BENCH_DIR/results"
LOG_DIR="$BENCH_DIR/logs"
mkdir -p "$RESULT_DIR" "$LOG_DIR"

MYSQL_CONTAINER="${MYSQL_CONTAINER:-12306-mysql}"
REDIS_CONTAINER="${REDIS_CONTAINER:-12306-redis}"
NODE_A="${NODE_A:-http://127.0.0.1:9005}"
NODE_B="${NODE_B:-http://127.0.0.1:9006}"

sql() {
  docker exec -i "$MYSQL_CONTAINER" mysql -uroot -proot --default-character-set=utf8mb4 -N -B 12306 -e "$1" 2>/dev/null
}

rcli() {
  docker exec -i "$REDIS_CONTAINER" redis-cli -a 123456 --no-auth-warning "$@"
}

redis_total_cmds() {
  rcli INFO stats | tr -d '\r' | awk -F: '/^total_commands_processed/{print $2}'
}

# 按模式删除 key（SCAN，不用 KEYS）
redis_del_pattern() {
  local keys
  keys="$(rcli --scan --pattern "$1" | tr -d '\r')"
  if [ -n "$keys" ]; then
    echo "$keys" | while IFS= read -r k; do rcli DEL "$k" >/dev/null; done
  fi
}

require() {
  command -v "$1" >/dev/null 2>&1 || { echo "缺少命令：$1 ${2:-}"; exit 1; }
}

check_node() {
  curl -sf "$1/actuator/health" >/dev/null 2>&1
}

json_field() {
  # 极简 JSON 取值（避免依赖 jq）：json_field '"hitRate":' <<< "$json"
  sed -n "s/.*$1\([^,}]*\).*/\1/p" | head -1
}
