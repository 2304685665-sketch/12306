#!/usr/bin/env bash
# 一次跑完新增实验（只打包一次，约 25 分钟）：
#   实验 4 异步关单  /  实验 5 下单失败补偿  /  实验 6 Lua 原子限流  /  实验 1 重复 3 轮
source "$(dirname "$0")/common.sh"
set +e
bash "$BENCH_DIR/run-close-bench.sh"
SKIP_BUILD=1 bash "$BENCH_DIR/run-compensate-bench.sh"
SKIP_BUILD=1 bash "$BENCH_DIR/run-ratelimit-bench.sh"
SKIP_BUILD=1 bash "$BENCH_DIR/run-query-repeat.sh"
ALL="$RESULT_DIR/NEW-$(date +%Y%m%d-%H%M%S).txt"
for p in close compensate ratelimit query-repeat; do
  f="$(ls -t "$RESULT_DIR"/$p-*.txt 2>/dev/null | head -1)"
  [ -n "$f" ] && { echo "######## $(basename "$f")"; cat "$f"; echo; }
done > "$ALL"
echo
echo "全部完成，把这个文件的内容发给我：$ALL"
