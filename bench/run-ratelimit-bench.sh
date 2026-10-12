#!/usr/bin/env bash
# 实验 6：自研 Redis Lua 原子限流（双节点，50 并发，500 次请求，上限 50），与非原子实现对照，各重复 3 轮；另验证窗口到期重置
# 用法：bash bench/run-ratelimit-bench.sh   （会自行启停 9005、9006）
source "$(dirname "$0")/common.sh"
set +e
ensure_k6
ROUNDS="${ROUNDS:-3}"
TS="$(date +%Y%m%d-%H%M%S)"
OUT="$RESULT_DIR/ratelimit-$TS.txt"
{
echo "== 实验 6：Redis Lua 原子限流（双节点交替，50 并发，500 次请求，上限 50）=="
bash "$BENCH_DIR/stop-nodes.sh" >/dev/null 2>&1
SKIP_BUILD="${SKIP_BUILD:-0}" bash "$BENCH_DIR/start-nodes.sh" > "$LOG_DIR/start-ratelimit.log" 2>&1 || { echo "节点启动失败，见 $LOG_DIR/start-ratelimit.log"; exit 1; }
printf "round\tmode\ttotal\tlimit\tallowed\trejected\terrors\tredis_counter\n"
for r in $(seq 1 "$ROUNDS"); do
  for mode in lua naive; do
    key="$mode-$TS-$r"
    line="$(k6 run -q -e MODE="$mode" -e KEY="$key" -e NODES="$NODE_A,$NODE_B" "$BENCH_DIR/k6/rate-limit.js" 2>/dev/null)"
    counter="$(rcli GET "index12306-ticket-service:rate-limit:bench:$key" | tr -d '\r')"
    printf "%s\t%s\t%s\n" "$r" "$line" "${counter:--}"
  done
done
echo
echo "== 窗口重置：上限 5、窗口 1s，连续 10 次 -> 等 1.2s -> 再 10 次 =="
key="reset-$TS"
first=0; second=0
for i in $(seq 1 10); do
  [ "$(curl -s -X POST "$NODE_A/api/ticket-service/bench/rate-limit/acquire?key=$key&limit=5&windowMs=1000&mode=lua" | json_field '"data":')" = "true" ] && first=$((first + 1))
done
sleep 1.2
for i in $(seq 1 10); do
  [ "$(curl -s -X POST "$NODE_B/api/ticket-service/bench/rate-limit/acquire?key=$key&limit=5&windowMs=1000&mode=lua" | json_field '"data":')" = "true" ] && second=$((second + 1))
done
echo "第一个窗口放行 ${first}/10，窗口过期后放行 ${second}/10（期望 5 / 5）"
bash "$BENCH_DIR/stop-nodes.sh" >/dev/null
} 2>&1 | grep -v 'level=warning' | tee "$OUT"
echo
echo "完成：$OUT"
