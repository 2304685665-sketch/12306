#!/usr/bin/env bash
# 验证余票缓存冷启动修复（约 8 分钟，重新打包一次）：实验 7 冷启动并发购票 + 实验 5 下单失败补偿重跑
source "$(dirname "$0")/common.sh"
set +e
MARK="$(mktemp)"
trap 'rm -f "$MARK"' EXIT
sleep 1
bash "$BENCH_DIR/run-coldstart-bench.sh"
SKIP_BUILD=1 bash "$BENCH_DIR/run-compensate-bench.sh"
bash "$BENCH_DIR/stop-nodes.sh" >/dev/null 2>&1
ALL="$RESULT_DIR/VERIFY-$(date +%Y%m%d-%H%M%S).txt"
{
  echo "分支：$(cd "$ROOT_DIR" && git branch --show-current) @ $(cd "$ROOT_DIR" && git rev-parse --short HEAD)，未提交改动 $(cd "$ROOT_DIR" && git status --porcelain | wc -l | tr -d ' ') 个"
  echo
  for p in coldstart compensate; do
    f="$(find "$RESULT_DIR" -maxdepth 1 -name "$p-*.txt" -newer "$MARK" 2>/dev/null | sort | tail -1)"
    if [ -n "$f" ]; then echo "######## $(basename "$f")"; cat "$f"; echo; else echo "######## ${p}：本次没有产生结果文件"; echo; fi
  done
} > "$ALL"
echo
echo "全部完成，把这个文件的内容发给我：$ALL"
