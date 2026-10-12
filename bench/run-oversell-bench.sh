#!/usr/bin/env bash
# 实验 2b：双节点并发抢票（500 用户 / 50 并发 / 库存 140），对比 v1（车次级分布式锁）与 v2（令牌桶 + 车次席别两级锁）
# 用法：bash bench/run-oversell-bench.sh   （先 bash bench/start-nodes.sh 起 9005、9006）
source "$(dirname "$0")/common.sh"
ensure_k6
USERS="${USERS:-500}"
VUS="${VUS:-50}"
VERSIONS="${VERSIONS:-v1 v2}"
check_node "$NODE_A" || { echo "$NODE_A 不可用"; exit 1; }
check_node "$NODE_B" || { echo "$NODE_B 不可用，需要两个节点"; exit 1; }

TS="$(date +%Y%m%d-%H%M%S)"
OUT="$RESULT_DIR/oversell-$TS.tsv"
printf "version\tusers\tsuccess\tsold_out\tother_fail\tavg_ms\tp50_ms\tp95_ms\tp99_ms\treq_per_s\tstock_before\tdb_tickets\tdup_seats\tseats_locked\ttoken_left\tremaining_cache\n" > "$OUT"

for v in $VERSIONS; do
  bash "$BENCH_DIR/reset-purchase-data.sh"
  STOCK="$(sql "select count(*) from t_seat where train_id=2 and seat_type=1 and start_station='北京南' and end_station='杭州东' and seat_status=0")"
  echo ">>> [$v] $USERS 个用户 / $VUS 并发 / 双节点抢 $STOCK 张一等座"
  LINE="$(k6 run -q -e VERSION="$v" -e USERS="$USERS" -e VUS="$VUS" -e NODES="$NODE_A,$NODE_B" \
    -e SUMMARY="$RESULT_DIR/oversell-$TS-$v.json" "$BENCH_DIR/k6/purchase-oversell.js")"
  DB_TICKETS="$(sql "select count(*) from t_ticket where train_id=2 and del_flag=0")"
  DUP="$(sql "select count(*) from (select carriage_number, seat_number from t_ticket where train_id=2 and del_flag=0 group by carriage_number, seat_number having count(*) > 1) t")"
  LOCKED="$(sql "select count(*) from t_seat where train_id=2 and seat_type=1 and start_station='北京南' and end_station='杭州东' and seat_status<>0")"
  TOKEN="$(rcli HGET "index12306-ticket-service:ticket_availability_token_bucket:2" "北京南_杭州东_1" | tr -d '\r')"
  REMAIN="$(rcli HGET "index12306-ticket-service:train_station_remaining_ticket:2_北京南_杭州东" "1" | tr -d '\r')"
  printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\n" "$LINE" "$STOCK" "$DB_TICKETS" "$DUP" "$LOCKED" "${TOKEN:--}" "${REMAIN:--}" | tee -a "$OUT"
done

echo
echo "结果：$OUT"
column -t -s $'\t' "$OUT"
echo "（实验完成后可执行 bash bench/reset-purchase-data.sh 还原数据）"
