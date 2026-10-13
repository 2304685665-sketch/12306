#!/usr/bin/env bash
# 实验 6：自研 Redis Lua 原子限流（双节点，50 并发，500 次请求，上限 50），与非原子实现对照，各重复 3 轮；另验证窗口到期重置
# 用法：bash bench/run-ratelimit-bench.sh   （会自行启停 9005、9006）
#       APP_ONLY=1 bash bench/run-ratelimit-bench.sh   只跑真实查票接口接入与关闭回归
source "$(dirname "$0")/common.sh"
set +e
ensure_k6
ROUNDS="${ROUNDS:-3}"
APP_PERMITS="${APP_PERMITS:-20}"
TS="$(date +%Y%m%d-%H%M%S)"
OUT="$RESULT_DIR/ratelimit-$TS.txt"
{
if [ "${APP_ONLY:-0}" != 1 ]; then
echo "== 实验 6：Redis Lua 原子限流（双节点交替，50 并发，500 次请求，上限 50）=="
bash "$BENCH_DIR/stop-nodes.sh" >/dev/null 2>&1
SKIP_BUILD="${SKIP_BUILD:-0}" bash "$BENCH_DIR/start-nodes.sh" > "$LOG_DIR/start-ratelimit.log" 2>&1 || { echo "节点启动失败，见 $LOG_DIR/start-ratelimit.log"; exit 1; }
printf "round\tmode\ttotal\tlimit\tallowed\trejected\terrors\tnodeA(req/allowed)\tnodeB(req/allowed)\tredis_counter\n"
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
fi

echo
echo "== 查票真实接口接入：重启双节点并开启 ticket.query.rate-limit.permits-per-second=$APP_PERMITS =="
bash "$BENCH_DIR/stop-nodes.sh" >/dev/null 2>&1
if [ "${APP_ONLY:-0}" = 1 ]; then build="${SKIP_BUILD:-0}"; else build=1; fi
SKIP_BUILD="$build" EXTRA_ARGS="--ticket.query.rate-limit.permits-per-second=$APP_PERMITS" bash "$BENCH_DIR/start-nodes.sh" > "$LOG_DIR/start-ratelimit-app.log" 2>&1 || { echo "节点启动失败，见 $LOG_DIR/start-ratelimit-app.log"; exit 1; }
# 未登录按 IP 计数，两节点看到的都是 127.0.0.1，即同一个 key；k6 50 并发打 300 次真实 /ticket/query，按每秒窗口统计放行数
k6 run -q -e NODES="$NODE_A,$NODE_B" -e TOTAL="${APP_TOTAL:-300}" -e PERMITS="$APP_PERMITS" "$BENCH_DIR/k6/rate-limit-app.js" 2>/dev/null
bash "$BENCH_DIR/stop-nodes.sh" >/dev/null

echo
echo "== 回归：默认配置（限流关闭）下 300 次查票不应出现「查询过于频繁」=="
SKIP_BUILD=1 bash "$BENCH_DIR/start-nodes.sh" > "$LOG_DIR/start-ratelimit-off.log" 2>&1 || { echo "节点启动失败，见 $LOG_DIR/start-ratelimit-off.log"; exit 1; }
k6 run -q -e NODES="$NODE_A,$NODE_B" -e TOTAL="${APP_TOTAL:-300}" -e PERMITS=0 "$BENCH_DIR/k6/rate-limit-app.js" 2>/dev/null
bash "$BENCH_DIR/stop-nodes.sh" >/dev/null
} 2>&1 | grep -v 'level=warning' | tee "$OUT"
echo
echo "完成：$OUT"
