#!/usr/bin/env bash
# 实验 1：车票查询多级缓存对照（单节点、50 并发、每组 30s，组前 10s 预热）
#   v1        原始实现：逐车次 GET 票价、逐席别 HGET 余票
#   v2        Pipeline 批量读 Redis
#   v3        Caffeine L1（站点/车次/票价）+ 余票一次 Pipeline
#   v3-static v3 去掉余票读取，用来量化「余票读取」这部分 Redis 开销
# 另外两组：无车次线路（杭州->北京）v1 vs v3，验证空值缓存防穿透
# 用法：bash bench/run-query-bench.sh     （先 bash bench/start-nodes.sh）
source "$(dirname "$0")/common.sh"
require k6 "（brew install k6）"
require curl

BASE="${BASE:-$NODE_A}"
VUS="${VUS:-50}"
DURATION="${DURATION:-30s}"
WARMUP="${WARMUP:-10s}"
check_node "$BASE" || { echo "$BASE 不可用，先 bash bench/start-nodes.sh"; exit 1; }

TS="$(date +%Y%m%d-%H%M%S)"
OUT="$RESULT_DIR/query-$TS.tsv"
printf "group\tversion\troute\treqs\tqps\tavg_ms\tp50_ms\tp95_ms\tp99_ms\tok_%%\tredis_cmds_per_req\tl1_hit_rate\n" > "$OUT"

run_group() {
  local group="$1" version="$2" from="$3" to="$4" expect="$5"
  echo ">>> [$group] $version $from->$to 预热 $WARMUP"
  k6 run -q -e BASE="$BASE" -e VERSION="$version" -e FROM="$from" -e TO="$to" -e VUS="$VUS" -e DURATION="$WARMUP" "$BENCH_DIR/k6/query.js" >/dev/null
  curl -s -X POST "$BASE/api/ticket-service/bench/local-cache/stats/reset" >/dev/null
  local before after tsv line reqs cmds hit
  before="$(redis_total_cmds)"
  tsv="$RESULT_DIR/.tmp-$group.tsv"
  k6 run -q -e BASE="$BASE" -e VERSION="$version" -e FROM="$from" -e TO="$to" -e VUS="$VUS" -e DURATION="$DURATION" \
    -e EXPECT_TRAINS="$expect" -e SUMMARY="$RESULT_DIR/query-$TS-$group.json" -e TSV="$tsv" "$BENCH_DIR/k6/query.js" >/dev/null
  after="$(redis_total_cmds)"
  line="$(cat "$tsv")"; rm -f "$tsv"
  reqs="$(echo "$line" | cut -f2)"
  cmds="$(awk -v a="$after" -v b="$before" -v r="$reqs" 'BEGIN{printf "%.2f", (a-b)/r}')"
  hit="$(curl -s "$BASE/api/ticket-service/bench/local-cache/stats" | json_field '"hitRate":')"
  case "$version" in v3*) ;; *) hit="-" ;; esac
  printf "%s\t%s\t%s->%s\t%s\t%s\t%s\n" "$group" "$version" "$from" "$to" "$(echo "$line" | cut -f2-)" "$cmds" "$hit" | tee -a "$OUT"
}

run_group A v1        BJP HZH 4
run_group B v2        BJP HZH 4
run_group C v3        BJP HZH 4
run_group D v3-static BJP HZH 4
run_group E v1        HZH BJP 0
run_group F v3        HZH BJP 0

echo
echo "结果：$OUT"
column -t -s $'\t' "$OUT"
