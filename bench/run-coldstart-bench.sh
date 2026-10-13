#!/usr/bin/env bash
# 实验 7：余票缓存冷启动并发购票（库存 140，50 个用户同时下单，人数少于库存，任何「余票不足」都是误拒）
# 验证 SeatMarginCacheLoader 二次检查修复：期望 v1 / v2 均为 50 人成功、0 人被拒
# 用法：bash bench/run-coldstart-bench.sh   （会自行启停 9005、9006）
source "$(dirname "$0")/common.sh"
set +e
ensure_k6
USERS="${USERS:-50}"
ROUNDS="${ROUNDS:-3}"
TS="$(date +%Y%m%d-%H%M%S)"
OUT="$RESULT_DIR/coldstart-$TS.txt"
{
echo "== 实验 7：余票缓存冷启动并发购票（$USERS 个用户同时下单，库存 140，每轮清空缓存，$ROUNDS 轮）=="
bash "$BENCH_DIR/stop-nodes.sh" >/dev/null 2>&1
SKIP_BUILD="${SKIP_BUILD:-0}" bash "$BENCH_DIR/start-nodes.sh" > "$LOG_DIR/start-coldstart.log" 2>&1 || { echo "节点启动失败，见 $LOG_DIR/start-coldstart.log"; exit 1; }
printf "round\tversion\tusers\tsuccess\tfalse_sold_out\tother_fail\tdb_tickets\tdup_seats\n"
for r in $(seq 1 "$ROUNDS"); do
  for v in v1 v2; do
    bash "$BENCH_DIR/reset-purchase-data.sh" >/dev/null
    LINE="$(k6 run -q -e VERSION="$v" -e USERS="$USERS" -e VUS="$USERS" -e NODES="$NODE_A,$NODE_B" "$BENCH_DIR/k6/purchase-oversell.js" 2>/dev/null)"
    tickets="$(sql "select count(*) from t_ticket where train_id=2 and del_flag=0")"
    dup="$(sql "select count(*) from (select carriage_number, seat_number from t_ticket where train_id=2 and del_flag=0 group by carriage_number, seat_number having count(*) > 1) t")"
    printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n" "$r" "$v" "$USERS" "$(echo "$LINE" | cut -f3)" "$(echo "$LINE" | cut -f4)" "$(echo "$LINE" | cut -f5)" "$tickets" "$dup"
  done
done
echo "（人数少于库存，false_sold_out 应为 0）"
bash "$BENCH_DIR/reset-purchase-data.sh" >/dev/null
bash "$BENCH_DIR/stop-nodes.sh" >/dev/null
} 2>&1 | grep -v 'level=warning' | tee "$OUT"
echo
echo "完成：$OUT"
