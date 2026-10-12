#!/usr/bin/env bash
# 实验 2a：双节点本地缓存失效广播（本地失效 + Redis Pub/Sub + TTL 兜底）的全局生效耗时
# 用法：bash bench/run-consistency-bench.sh   （先 bash bench/start-nodes.sh 起 9005、9006）
source "$(dirname "$0")/common.sh"
require k6 "（brew install k6）"
ROUNDS="${ROUNDS:-100}"
check_node "$NODE_A" || { echo "$NODE_A 不可用"; exit 1; }
check_node "$NODE_B" || { echo "$NODE_B 不可用，需要两个节点"; exit 1; }

ORIGINAL_CENTS="$(sql "select price from t_train_station_price where train_id=2 and departure='北京南' and arrival='杭州东' and seat_type=1 and del_flag=0 limit 1")"
echo ">>> G39 北京南->杭州东 一等座原价：$ORIGINAL_CENTS 分，执行 $ROUNDS 轮调价"
LATENCY_KEY="index12306-ticket-service:bench:local-cache-invalidate-latency-us"
rcli DEL "$LATENCY_KEY" >/dev/null

TS="$(date +%Y%m%d-%H%M%S)"
LINE="$(k6 run -q -e NODE_A="$NODE_A" -e NODE_B="$NODE_B" -e ORIGINAL_CENTS="$ORIGINAL_CENTS" -e ROUNDS="$ROUNDS" \
  -e SUMMARY="$RESULT_DIR/consistency-$TS.json" "$BENCH_DIR/k6/price-consistency.js")"

NOW_CENTS="$(sql "select price from t_train_station_price where train_id=2 and departure='北京南' and arrival='杭州东' and seat_type=1 and del_flag=0 limit 1")"

# 节点 B 监听器记录的「发布 -> B 完成本地失效」耗时（微秒）
STATS="$(rcli LRANGE "$LATENCY_KEY" 0 -1 | tr -d '\r' | awk -F: 'NF==2{print $2}' | sort -n | awk '
  {a[NR]=$1; s+=$1}
  END{ if(NR==0){print "0\t0\t0\t0\t0"; exit}
       p50=a[int((NR-1)*0.50)+1]; p99=a[int((NR-1)*0.99)+1];
       printf "%d\t%.0f\t%d\t%d\t%d", NR, s/NR, p50, p99, a[NR] }')"

OUT="$RESULT_DIR/consistency-$TS.tsv"
{
  printf "rounds\te2e_avg_ms\te2e_p50_ms\te2e_p99_ms\te2e_max_ms\tstale_reads\tfirst_read_fresh_%%\ttimeouts\n"
  echo "$LINE"
  printf "\nbroadcast_msgs\tpubsub_avg_us\tpubsub_p50_us\tpubsub_p99_us\tpubsub_max_us\n"
  echo "$STATS"
  printf "\n价格已还原：%s 分（原价 %s 分）\n" "$NOW_CENTS" "$ORIGINAL_CENTS"
} | tee "$OUT"
