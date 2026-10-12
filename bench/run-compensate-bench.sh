#!/usr/bin/env bash
# 实验 5：令牌扣减后下单失败的补偿
#   注入 20% 订单创建失败（座位已锁定、车票已落库后抛异常，DB 事务回滚），100 个用户各买 1 张（库存 140，不会售罄）
#   对照：compensate-on-failure=false（原实现） vs true（修复后：归还令牌 + 余票缓存改为事务提交后扣减）
#   判定：令牌 / 余票缓存 是否等于 DB 实际可售座位
# 用法：bash bench/run-compensate-bench.sh   （会自行启停 9005、9006）
source "$(dirname "$0")/common.sh"
set +e
ensure_k6
USERS="${USERS:-100}"
FAIL_RATE="${FAIL_RATE:-0.2}"
TS="$(date +%Y%m%d-%H%M%S)"
OUT="$RESULT_DIR/compensate-$TS.txt"
TB_KEY="index12306-ticket-service:ticket_availability_token_bucket:2"
REM_KEY="index12306-ticket-service:train_station_remaining_ticket:2_北京南_杭州东"
{
echo "== 实验 5：下单失败补偿（注入失败率 ${FAIL_RATE}，$USERS 个用户，库存 140）=="
printf "compensate\tsuccess\tinjected_fail\tdb_tickets\tdb_available\ttoken\tremaining\ttoken_leak\tremaining_leak\n"
build="${SKIP_BUILD:-0}"
for flag in false true; do
  bash "$BENCH_DIR/stop-nodes.sh" >/dev/null 2>&1
  EXTRA_ARGS="--ticket.bench.order-fail-rate=$FAIL_RATE --ticket.purchase.compensate-on-failure=$flag" SKIP_BUILD="$build" \
    bash "$BENCH_DIR/start-nodes.sh" > "$LOG_DIR/start-compensate-$flag.log" 2>&1 || { echo "节点启动失败，见 $LOG_DIR/start-compensate-$flag.log"; exit 1; }
  build=1
  bash "$BENCH_DIR/reset-purchase-data.sh" >/dev/null
  LINE="$(k6 run -q -e VERSION=v2 -e USERS="$USERS" -e VUS=20 -e NODES="$NODE_A,$NODE_B" \
    -e SUMMARY="$RESULT_DIR/compensate-$TS-$flag.json" "$BENCH_DIR/k6/purchase-oversell.js" 2>/dev/null)"
  success="$(echo "$LINE" | cut -f3)"
  failed="$(echo "$LINE" | cut -f5)"
  tickets="$(sql "select count(*) from t_ticket where train_id=2 and del_flag=0")"
  available="$(sql "select count(*) from t_seat where train_id=2 and seat_type=1 and start_station='北京南' and end_station='杭州东' and seat_status=0")"
  token="$(rcli HGET "$TB_KEY" "北京南_杭州东_1" | tr -d '\r')"
  remain="$(rcli HGET "$REM_KEY" 1 | tr -d '\r')"
  printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n" "$flag" "$success" "$failed" "$tickets" "$available" "$token" "$remain" \
    "$((available - ${token:-0}))" "$((available - ${remain:-0}))"
done
echo "（token_leak / remaining_leak = DB 可售座位 - 缓存值；> 0 表示有座位因缓存少计而卖不出去）"
bash "$BENCH_DIR/reset-purchase-data.sh" >/dev/null
bash "$BENCH_DIR/stop-nodes.sh" >/dev/null
} 2>&1 | grep -v 'level=warning' | tee "$OUT"
echo
echo "完成，把这个文件的内容发给我：$OUT"
