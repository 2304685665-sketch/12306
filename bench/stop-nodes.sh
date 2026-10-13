#!/usr/bin/env bash
# 停止 start-nodes.sh 启动的节点，并等待进程真正退出、端口释放后才返回
# （只发 SIGTERM 就返回会导致紧接着的下一个实验启动时报「端口已被占用」）
source "$(dirname "$0")/common.sh"
PIDS=""
for f in "$LOG_DIR"/node-*.pid; do
  [ -f "$f" ] || continue
  pid="$(cat "$f")"
  if kill "$pid" 2>/dev/null; then
    echo "stopping $pid ($(basename "$f" .pid))"
    PIDS="$PIDS $pid"
  fi
  rm -f "$f"
done

# Spring Boot 优雅关闭通常几秒内完成，最多等 30 秒，仍未退出则强制结束
for pid in $PIDS; do
  for i in $(seq 1 60); do
    kill -0 "$pid" 2>/dev/null || break
    sleep 0.5
  done
  if kill -0 "$pid" 2>/dev/null; then
    echo "进程 $pid 30 秒未退出，强制结束"
    kill -9 "$pid" 2>/dev/null
  fi
done

for port in ${PORTS:-9005 9006}; do
  for i in $(seq 1 20); do
    lsof -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1 || break
    sleep 0.5
  done
done
exit 0
