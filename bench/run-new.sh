#!/usr/bin/env bash
# 一次跑完新增实验（只打包一次，约 30 分钟）：
#   实验 4 异步关单  /  实验 5 下单失败补偿  /  实验 6 Lua 原子限流  /  实验 1 重复 3 轮
# 汇总文件只收集本次运行产生的结果，某项失败时会写明，不会混入旧结果
source "$(dirname "$0")/common.sh"
set +e
MARK="$(mktemp)"
trap 'rm -f "$MARK"' EXIT
sleep 1

bash "$BENCH_DIR/run-close-bench.sh"
SKIP_BUILD=1 bash "$BENCH_DIR/run-compensate-bench.sh"
SKIP_BUILD=1 bash "$BENCH_DIR/run-ratelimit-bench.sh"
SKIP_BUILD=1 bash "$BENCH_DIR/run-query-repeat.sh"
bash "$BENCH_DIR/stop-nodes.sh" >/dev/null 2>&1

ALL="$RESULT_DIR/NEW-$(date +%Y%m%d-%H%M%S).txt"
{
  echo "环境：$(sysctl -n machdep.cpu.brand_string 2>/dev/null || uname -m)，$(sysctl -n hw.ncpu 2>/dev/null) 核"
  echo "分支：$(cd "$ROOT_DIR" && git branch --show-current) @ $(cd "$ROOT_DIR" && git rev-parse --short HEAD)，未提交改动 $(cd "$ROOT_DIR" && git status --porcelain | wc -l | tr -d ' ') 个"
  echo
  for p in close compensate ratelimit query-repeat; do
    f="$(find "$RESULT_DIR" -maxdepth 1 -name "$p-*.txt" -newer "$MARK" 2>/dev/null | sort | tail -1)"
    if [ -n "$f" ]; then
      echo "######## $(basename "$f")"; cat "$f"; echo
    else
      echo "######## ${p}：本次没有产生结果文件"; echo
    fi
  done
} > "$ALL"
echo
echo "全部完成，把这个文件的内容发给我：$ALL"
