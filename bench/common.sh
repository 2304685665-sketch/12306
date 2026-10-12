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

# 没有 k6 时自动下载到 bench/.bin（不需要 Homebrew）
ensure_k6() {
  export PATH="$BENCH_DIR/.bin:$PATH"
  command -v k6 >/dev/null 2>&1 && return 0
  local ver="v0.54.0" arch zip
  case "$(uname -m)" in arm64|aarch64) arch=arm64 ;; *) arch=amd64 ;; esac
  zip="k6-$ver-macos-$arch"
  echo ">>> 未检测到 k6，下载 $zip"
  mkdir -p "$BENCH_DIR/.bin"
  if curl -fsSL -o "/tmp/$zip.zip" "https://github.com/grafana/k6/releases/download/$ver/$zip.zip" \
     && unzip -oq "/tmp/$zip.zip" -d /tmp && cp "/tmp/$zip/k6" "$BENCH_DIR/.bin/k6" && chmod +x "$BENCH_DIR/.bin/k6"; then
    echo ">>> k6 已就绪：$(k6 version | head -1)"
  elif command -v brew >/dev/null 2>&1; then
    brew install k6
  else
    echo "k6 下载失败，且没有 Homebrew"; return 1
  fi
}

# 默认 java 低于 17 时，自动切换到本机已安装的 17+
ensure_java17() {
  local major
  major="$(java -version 2>&1 | awk -F'"' '/version/{print $2}' | cut -d. -f1)"
  if [ "${major:-0}" -lt 17 ] && [ -x /usr/libexec/java_home ]; then
    local home
    home="$(/usr/libexec/java_home -v 17+ 2>/dev/null || true)"
    if [ -n "$home" ]; then export JAVA_HOME="$home" PATH="$home/bin:$PATH"; fi
  fi
  major="$(java -version 2>&1 | awk -F'"' '/version/{print $2}' | cut -d. -f1)"
  [ "${major:-0}" -ge 17 ] || { echo "需要 JDK 17+，当前：$(java -version 2>&1 | head -1)"; return 1; }
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
