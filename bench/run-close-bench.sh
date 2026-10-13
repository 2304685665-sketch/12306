#!/usr/bin/env bash
# 实验 4：异步关单链路（RocketMQ 延迟消息 -> 关闭订单 -> 回滚座位 / 余票缓存 / 令牌桶）
#   4a 时效：延迟级别临时缩短为 1s，50 个未支付订单，测「消息发出 -> 回滚完成」耗时与全部恢复所需时间
#   4b 幂等：20 个未支付订单，每单并发投递 5 次关单消息（MQ 重投 / 业务重复两种），验证只回滚一次
# 用法：bash bench/run-close-bench.sh   （会自行启停 9005、9006）
#       ONLY_REPLAY=1 bash bench/run-close-bench.sh   只跑 4b
source "$(dirname "$0")/common.sh"
set +e
ensure_k6
USERS="${USERS:-50}"
REPLAY_USERS="${REPLAY_USERS:-20}"
REPLAY="${REPLAY:-5}"
TS="$(date +%Y%m%d-%H%M%S)"
OUT="$RESULT_DIR/close-$TS.txt"
LAT_KEY="index12306-ticket-service:bench:delay-close-latency-ms"
TB_KEY="index12306-ticket-service:ticket_availability_token_bucket:2"
REM_KEY="index12306-ticket-service:train_station_remaining_ticket:2_北京南_杭州东"

now_ms() { perl -MTime::HiRes=time -e 'printf "%d\n", time*1000'; }

orders_with_status() {
  local q="" i
  for i in $(seq 0 15); do q="$q${q:+ union all }select count(*) c from t_order_$i where train_id=2 and status=$1 and del_flag=0"; done
  sql "select sum(c) from ($q) t"
}

# 输出：令牌 余票缓存 锁定座位 已关闭订单 待支付订单
state() {
  local token remain locked
  token="$(rcli HGET "$TB_KEY" "北京南_杭州东_1" | tr -d '\r')"
  remain="$(rcli HGET "$REM_KEY" 1 | tr -d '\r')"
  locked="$(sql "select count(*) from t_seat where train_id=2 and seat_type=1 and start_station='北京南' and end_station='杭州东' and seat_status<>0")"
  printf "%s\t%s\t%s\t%s\t%s" "${token:--}" "${remain:--}" "$locked" "$(orders_with_status 30)" "$(orders_with_status 0)"
}

latency_stats() {
  rcli LRANGE "$LAT_KEY" 0 -1 | tr -d '\r' | grep -E '^[0-9]+$' | sort -n | awk -v d="$1" '
    {a[NR]=$1-d; s+=$1-d}
    END{ if(NR==0){print "0\t-\t-\t-\t-"; exit}
         printf "%d\t%.0f\t%d\t%d\t%d", NR, s/NR, a[int((NR-1)*0.5)+1], a[int((NR-1)*0.99)+1], a[NR] }'
}

{
if [ "${ONLY_REPLAY:-0}" != 1 ]; then
echo "== 实验 4a：关单时效（延迟级别 1 = 1s，$USERS 个未支付订单）=="
bash "$BENCH_DIR/stop-nodes.sh" >/dev/null 2>&1
EXTRA_ARGS="--order.delay-close.level=1" SKIP_BUILD="${SKIP_BUILD:-0}" bash "$BENCH_DIR/start-nodes.sh" || { echo "节点启动失败"; exit 1; }
bash "$BENCH_DIR/reset-purchase-data.sh"
rcli DEL "$LAT_KEY" >/dev/null
k6 run -q -e MODE=latency -e USERS="$USERS" -e NODES="$NODE_A,$NODE_B" -e SUMMARY="$RESULT_DIR/close-$TS-latency.json" "$BENCH_DIR/k6/order-close.js" >/dev/null
t0="$(now_ms)"
printf "购票完成时状态（令牌 余票 锁座 已关单 待支付）：%s\n" "$(state)"
restored=0
for i in $(seq 1 600); do
  st="$(state)"
  if [ "$st" = "$(printf '140\t140\t0\t%s\t0' "$USERS")" ]; then restored=1; break; fi
  sleep 0.2
done
t1="$(now_ms)"
printf "最终状态（令牌 余票 锁座 已关单 待支付）：%s\n" "$(state)"
if [ "$restored" = 1 ]; then echo "购票结束后 $((t1 - t0)) ms 全部 $USERS 单关闭并恢复到 140"; else echo "120s 内未完全恢复"; fi
printf "\nrollbacks\tavg_ms\tp50_ms\tp99_ms\tmax_ms   （订单消息发出 -> 回滚完成，已扣除 1000ms 延迟）\n"
latency_stats 1000
echo
fi

echo "== 实验 4b：关单消息重复投递（$REPLAY_USERS 单 × 每单并发 $REPLAY 次）=="
bash "$BENCH_DIR/stop-nodes.sh" >/dev/null 2>&1
# 跳过 4a 时由 4b 负责打包
if [ "${ONLY_REPLAY:-0}" = 1 ]; then build="${SKIP_BUILD:-0}"; else build=1; fi
SKIP_BUILD="$build" bash "$BENCH_DIR/start-nodes.sh" >/dev/null || { echo "节点启动失败"; exit 1; }
printf "replay_mode\torders\tdeliveries\trollbacks\ttoken\tremaining\tseats_locked\tclosed\tpending\n"
for mode in redelivery duplicate; do
  bash "$BENCH_DIR/reset-purchase-data.sh" >/dev/null
  rcli DEL "$LAT_KEY" >/dev/null
  k6 run -q -e MODE=replay -e REPLAY_MODE="$mode" -e USERS="$REPLAY_USERS" -e REPLAY="$REPLAY" -e NODES="$NODE_A,$NODE_B" \
    -e SUMMARY="$RESULT_DIR/close-$TS-$mode.json" "$BENCH_DIR/k6/order-close.js" >/dev/null
  sleep 2
  printf "%s\t%s\t%s\t%s\t%s\n" "$mode" "$REPLAY_USERS" "$((REPLAY_USERS * REPLAY))" "$(rcli LLEN "$LAT_KEY" | tr -d '\r')" "$(state)"
done
echo "（期望：rollbacks = 订单数，令牌 / 余票 = 140，锁座 = 0，待支付 = 0）"
bash "$BENCH_DIR/reset-purchase-data.sh" >/dev/null
bash "$BENCH_DIR/stop-nodes.sh" >/dev/null
} 2>&1 | grep -v 'level=warning' | tee "$OUT"
echo
echo "完成，把这个文件的内容发给我：$OUT"
