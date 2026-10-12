#!/usr/bin/env bash
# 实验 1 重复 N 轮（默认 3），汇总每组 QPS / P99 的中位数与最小-最大范围，用于说明波动
source "$(dirname "$0")/common.sh"
set +e
REPEAT="${REPEAT:-3}"
TS="$(date +%Y%m%d-%H%M%S)"
OUT="$RESULT_DIR/query-repeat-$TS.txt"
bash "$BENCH_DIR/stop-nodes.sh" >/dev/null 2>&1
PORTS="9005" SKIP_BUILD="${SKIP_BUILD:-0}" bash "$BENCH_DIR/start-nodes.sh" > "$LOG_DIR/start-query-repeat.log" 2>&1 || { echo "节点启动失败"; exit 1; }
FILES=""
for r in $(seq 1 "$REPEAT"); do
  echo ">>> 第 $r / $REPEAT 轮"
  bash "$BENCH_DIR/run-query-bench.sh" >/dev/null 2>&1
  FILES="$FILES $(ls -t "$RESULT_DIR"/query-2*.tsv | head -1)"
  sleep 1
done
bash "$BENCH_DIR/stop-nodes.sh" >/dev/null
{
echo "== 实验 1 重复 $REPEAT 轮（单节点，50 并发，每组 30s）=="
echo "原始文件：$FILES"
printf "group\tversion\troute\tqps_median\tqps_min\tqps_max\tp99_median_ms\tp99_min\tp99_max\tok_min_%%\n"
cat $FILES | grep -v '^group' | sort -k1,1 -s | awk -F'\t' '
  function med(arr, n,   i,j,t){ for(i=1;i<=n;i++)for(j=i+1;j<=n;j++)if(arr[j]<arr[i]){t=arr[i];arr[i]=arr[j];arr[j]=t} return (n%2)?arr[(n+1)/2]:(arr[n/2]+arr[n/2+1])/2 }
  function flush(){ if(g=="")return; printf "%s\t%s\t%s\t%.0f\t%.0f\t%.0f\t%.1f\t%.1f\t%.1f\t%.2f\n", g, v, rt, med(q,n), qmin, qmax, med(p,n), pmin, pmax, okmin }
  { if($1!=g){ flush(); g=$1; v=$2; rt=$3; n=0; qmin=1e18; qmax=0; pmin=1e18; pmax=0; okmin=100 }
    n++; q[n]=$5+0; p[n]=$9+0
    if($5<qmin)qmin=$5; if($5>qmax)qmax=$5; if($9<pmin)pmin=$9; if($9>pmax)pmax=$9; if($10<okmin)okmin=$10 }
  END{ flush() }'
} | tee "$OUT"
echo "完成：$OUT"
