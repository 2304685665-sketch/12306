#!/usr/bin/env bash
# 只重跑实验 2b（双节点并发抢票）：清缓存 -> 用已打好的 jar 起双节点 -> 抢票 -> 停节点
source "$(dirname "$0")/common.sh"
set +e
TS="$(date +%Y%m%d-%H%M%S)"
OUT="$RESULT_DIR/ALL-oversell-$TS.txt"
{
  echo "== 清理 ticket / user 服务的 Redis 缓存 =="
  before="$(rcli --scan --pattern 'index12306-ticket-service:*' | wc -l | tr -d ' ')"
  redis_del_pattern "index12306-ticket-service:*"
  redis_del_pattern "index12306-user-service:*"
  after="$(rcli --scan --pattern 'index12306-ticket-service:*' | wc -l | tr -d ' ')"
  echo "ticket-service key：$before -> $after"
} 2>&1 | tee "$OUT"
bash "$BENCH_DIR/stop-nodes.sh" >/dev/null 2>&1
SKIP_BUILD=1 bash "$BENCH_DIR/start-nodes.sh" 2>&1 | tee -a "$OUT" || { echo "节点启动失败" | tee -a "$OUT"; exit 1; }
trap 'bash "$BENCH_DIR/stop-nodes.sh"' EXIT
echo "== 实验 2b：双节点并发抢票 ==" | tee -a "$OUT"
bash "$BENCH_DIR/run-oversell-bench.sh" 2>&1 | grep -v 'level=warning' | tee -a "$OUT"
bash "$BENCH_DIR/reset-purchase-data.sh" >/dev/null 2>&1
echo
echo "完成，把这个文件的内容发给我：$OUT"
