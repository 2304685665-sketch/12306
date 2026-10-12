#!/usr/bin/env bash
# 一次跑完新增的实验 4（异步关单）和实验 5（下单失败补偿），只打包一次
source "$(dirname "$0")/common.sh"
set +e
bash "$BENCH_DIR/run-close-bench.sh"
SKIP_BUILD=1 bash "$BENCH_DIR/run-compensate-bench.sh"
echo
echo "两份结果：$(ls -t "$RESULT_DIR"/close-*.txt | head -1)  $(ls -t "$RESULT_DIR"/compensate-*.txt | head -1)"
