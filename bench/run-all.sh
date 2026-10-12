#!/usr/bin/env bash
# 一键跑完全部实验，最后把所有结果汇总到 bench/results/ALL-<时间>.txt
# 前置：docker compose up -d（MySQL / Redis / Nacos / RocketMQ），brew install k6，Java 17
# 注意：会占用 9005、9006 端口，IDEA 里启动的服务请先停掉
source "$(dirname "$0")/common.sh"
require k6 "（brew install k6）"
TS="$(date +%Y%m%d-%H%M%S)"
ALL="$RESULT_DIR/ALL-$TS.txt"

{
  echo "== 环境 =="
  echo "机器：$(sysctl -n machdep.cpu.brand_string 2>/dev/null || uname -m)，$(sysctl -n hw.ncpu 2>/dev/null) 核，内存 $(( $(sysctl -n hw.memsize 2>/dev/null || echo 0) / 1073741824 ))GB"
  echo "java：$(java -version 2>&1 | head -1)"
  echo "k6：$(k6 version 2>&1 | head -1)"
  echo
} | tee "$ALL"

echo "== 实验 3：分片路由单测 ==" | tee -a "$ALL"
bash "$BENCH_DIR/run-shard-test.sh" | tee -a "$ALL"
echo | tee -a "$ALL"

bash "$BENCH_DIR/stop-nodes.sh" >/dev/null 2>&1 || true
SKIP_BUILD="${SKIP_BUILD:-0}" bash "$BENCH_DIR/start-nodes.sh"
trap 'bash "$BENCH_DIR/stop-nodes.sh"' EXIT

echo "== 实验 1：查询多级缓存（单节点 9005，${VUS:-50} 并发，每组 ${DURATION:-30s}）==" | tee -a "$ALL"
bash "$BENCH_DIR/run-query-bench.sh" 2>&1 | tee -a "$ALL"
echo | tee -a "$ALL"

echo "== 实验 2a：双节点本地缓存失效广播 ==" | tee -a "$ALL"
bash "$BENCH_DIR/run-consistency-bench.sh" 2>&1 | tee -a "$ALL"
echo | tee -a "$ALL"

echo "== 实验 2b：双节点并发抢票 ==" | tee -a "$ALL"
bash "$BENCH_DIR/run-oversell-bench.sh" 2>&1 | tee -a "$ALL"
bash "$BENCH_DIR/reset-purchase-data.sh" >/dev/null

echo
echo "全部完成，把这个文件的内容发给我：$ALL"
