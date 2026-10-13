#!/usr/bin/env bash
# 只补跑还缺的实验（约 20 分钟，只打包一次），已有有效数据的实验 4a、实验 6 压测接口部分不再重复：
#   实验 4b 重复投递幂等 / 实验 5 下单失败补偿 / 实验 6 真实查票接口接入 + 关闭回归 / 实验 1 重复 3 轮
source "$(dirname "$0")/common.sh"
set +e
MARK="$(mktemp)"
trap 'rm -f "$MARK"' EXIT
sleep 1

ONLY_REPLAY=1 bash "$BENCH_DIR/run-close-bench.sh"
SKIP_BUILD=1 bash "$BENCH_DIR/run-compensate-bench.sh"
SKIP_BUILD=1 APP_ONLY=1 bash "$BENCH_DIR/run-ratelimit-bench.sh"
SKIP_BUILD=1 bash "$BENCH_DIR/run-query-repeat.sh"
bash "$BENCH_DIR/stop-nodes.sh" >/dev/null 2>&1

ALL="$RESULT_DIR/GAPS-$(date +%Y%m%d-%H%M%S).txt"
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
