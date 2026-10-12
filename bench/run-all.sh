#!/usr/bin/env bash
# 一键跑完全部实验：自动准备 k6 / JDK17 / 中间件 / 数据库备份，任何一组失败都会记录下来并继续，
# 最后把所有结果汇总到 bench/results/ALL-<时间>.txt
# 注意：会占用 9005、9006 端口（IDEA 里的服务请先停掉）
source "$(dirname "$0")/common.sh"
set +e
TS="$(date +%Y%m%d-%H%M%S)"
ALL="$RESULT_DIR/ALL-$TS.txt"
FAILED=""

stage() {
  local name="$1"; shift
  echo "== $name ==" | tee -a "$ALL"
  "$@" 2>&1 | tee -a "$ALL"
  local rc=${PIPESTATUS[0]}
  if [ "$rc" != 0 ]; then FAILED="$FAILED\n- ${name}（退出码 ${rc}）"; echo "!! $name 失败，继续下一项" | tee -a "$ALL"; fi
  echo | tee -a "$ALL"
  return "$rc"
}

prepare() {
  ensure_java17 || return 1
  ensure_k6 || return 1
  require docker
  if ! docker info >/dev/null 2>&1; then echo "Docker 没有运行，请先打开 Docker Desktop"; return 1; fi
  local running missing=0
  running="$(docker ps --format '{{.Names}}')"
  for c in "$MYSQL_CONTAINER" "$REDIS_CONTAINER" 12306-nacos 12306-rmq-namesrv 12306-rmq-broker; do
    echo "$running" | grep -x "$c" >/dev/null || missing=1
  done
  if [ "$missing" = 1 ]; then
    echo ">>> 启动中间件（命名卷 12306-mysql-data，不会覆盖已有数据）"
    (cd "$ROOT_DIR" && docker compose up -d) || return 1
    for i in $(seq 1 60); do sql "select 1" >/dev/null 2>&1 && break; sleep 3; done
    sleep 15
  fi
  sql "select 1" >/dev/null || { echo "MySQL 不可用"; return 1; }
  local backup="$HOME/12306-backup-$TS.sql"
  docker exec "$MYSQL_CONTAINER" mysqldump -uroot -proot 12306 > "$backup" 2>/dev/null
  echo ">>> 数据库已备份：${backup}（$(du -h "$backup" | cut -f1)）"
  echo "机器：$(sysctl -n machdep.cpu.brand_string 2>/dev/null || uname -m)，$(sysctl -n hw.ncpu 2>/dev/null) 核，内存 $(( $(sysctl -n hw.memsize 2>/dev/null || echo 0) / 1073741824 ))GB"
  echo "java：$(java -version 2>&1 | head -1)"
  echo "k6：$(k6 version 2>&1 | head -1)"
  echo "分支：$(cd "$ROOT_DIR" && git branch --show-current) @ $(cd "$ROOT_DIR" && git rev-parse --short HEAD)"
}

stage "准备环境" prepare || { echo "环境准备失败，后续实验无法执行，把上面的输出发给我。"; exit 1; }
stage "实验 3：分片路由单测" bash "$BENCH_DIR/run-shard-test.sh"

bash "$BENCH_DIR/stop-nodes.sh" >/dev/null 2>&1
if stage "启动双节点" bash "$BENCH_DIR/start-nodes.sh"; then
  trap 'bash "$BENCH_DIR/stop-nodes.sh"' EXIT
  stage "实验 1：查询多级缓存（单节点 9005，${VUS:-50} 并发，每组 ${DURATION:-30s}）" bash "$BENCH_DIR/run-query-bench.sh"
  stage "实验 2a：双节点本地缓存失效广播" bash "$BENCH_DIR/run-consistency-bench.sh"
  stage "实验 2b：双节点并发抢票" bash "$BENCH_DIR/run-oversell-bench.sh"
  bash "$BENCH_DIR/reset-purchase-data.sh" >/dev/null 2>&1
else
  echo "节点启动失败，日志：$LOG_DIR/node-9005.log / node-9006.log" | tee -a "$ALL"
  tail -40 "$LOG_DIR/node-9005.log" 2>/dev/null | tee -a "$ALL"
fi

if [ -n "$FAILED" ]; then printf "失败项：%b\n" "$FAILED" | tee -a "$ALL"; fi
echo
echo "全部完成，把这个文件的内容发给我：$ALL"
