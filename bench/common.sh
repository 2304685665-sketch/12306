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

# 按模式删除 key：在 Redis 容器内 SCAN + xargs 批量 DEL
# （不要在 while read 循环里调 docker exec -i，它会吞掉循环的标准输入，导致只删第一个 key）
redis_del_pattern() {
  docker exec "$REDIS_CONTAINER" sh -c "redis-cli -a 123456 --no-auth-warning --scan --pattern '$1' | xargs -r -n 200 redis-cli -a 123456 --no-auth-warning DEL" >/dev/null
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

java_major() {
  "$1" -version 2>&1 | awk -F'"' '/version/{print $2}' | sed 's/^1\.//' | cut -d. -f1
}

# 项目用的 Lombok 1.18.30 只支持到 JDK 21：默认 java 不在 17~21 时，
# 依次尝试本机已安装的 JDK 17 / 21，最后用 bench/.jdk 里自带的 JDK 17
ensure_java17() {
  local cur home m
  cur="$(java_major java)"
  if [ "${cur:-0}" -ge 17 ] && [ "${cur:-0}" -le 21 ]; then return 0; fi
  if [ -x /usr/libexec/java_home ]; then
    for v in 17 21; do
      home="$(/usr/libexec/java_home -v "$v" 2>/dev/null || true)"
      if [ -n "$home" ] && [ "$(java_major "$home/bin/java")" = "$v" ]; then
        export JAVA_HOME="$home" PATH="$home/bin:$PATH"
        echo ">>> 默认 java 是 ${cur}，切换到 JDK ${v}：$home"
        return 0
      fi
    done
  fi
  home="$(ls -d "$BENCH_DIR"/.jdk/*/Contents/Home "$BENCH_DIR"/.jdk/* 2>/dev/null | while read -r d; do [ -x "$d/bin/java" ] && echo "$d" && break; done)"
  if [ -n "$home" ]; then
    export JAVA_HOME="$home" PATH="$home/bin:$PATH"
    echo ">>> 默认 java 是 ${cur}，使用项目内置 JDK $(java_major "$home/bin/java")：$home"
    return 0
  fi
  echo "需要 JDK 17~21（当前 ${cur}），且没有找到可用的 JDK"
  return 1
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
