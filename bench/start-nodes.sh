#!/usr/bin/env bash
# 打包并启动 aggregation-service 节点（默认两个：9005 / 9006），开启压测接口、关闭 MyBatis SQL 打印
# 用法：bash bench/start-nodes.sh            # 打包 + 启动 9005、9006
#       SKIP_BUILD=1 bash bench/start-nodes.sh
#       PORTS="9005" bash bench/start-nodes.sh  # 只起一个节点
source "$(dirname "$0")/common.sh"
require docker
ensure_java17

RUNNING="$(docker ps --format '{{.Names}}')"
for c in "$MYSQL_CONTAINER" "$REDIS_CONTAINER" 12306-nacos 12306-rmq-namesrv 12306-rmq-broker; do
  if ! echo "$RUNNING" | grep -x "$c" >/dev/null; then echo "容器 $c 没在运行，先 docker compose up -d"; exit 1; fi
done

cd "$ROOT_DIR"
if [ "${SKIP_BUILD:-0}" != "1" ]; then
  echo ">>> 打包 aggregation-service（首次较慢）"
  rm -f "$ROOT_DIR/services/aggregation-service/target/index12306-aggregation-service.jar"
  ./mvnw -q -DskipTests -pl services/aggregation-service -am clean package
fi
JAR="$ROOT_DIR/services/aggregation-service/target/index12306-aggregation-service.jar"
[ -f "$JAR" ] || { echo "找不到 $JAR"; exit 1; }
# 校验 jar 里确实是本次修改后的代码，避免用旧包压测
TMP_LIB="$(mktemp -d)"
(cd "$TMP_LIB" && unzip -q -o "$JAR" 'BOOT-INF/lib/index12306-ticket-service-*.jar')
# 先完整取出清单再匹配：pipefail 下 `unzip | grep -q` 会因 grep 提前退出导致 unzip 收到 SIGPIPE 而误判失败
LISTING="$(unzip -l "$TMP_LIB"/BOOT-INF/lib/index12306-ticket-service-*.jar)"
if ! grep 'TicketQueryBenchController' <<< "$LISTING" >/dev/null; then
  echo "jar 中没有 TicketQueryBenchController，打包的不是最新代码"; rm -rf "$TMP_LIB"; exit 1
fi
rm -rf "$TMP_LIB"
echo ">>> jar 校验通过：$(ls -l "$JAR" | awk '{print $6, $7, $8}')"

PORTS="${PORTS:-9005 9006}"
for port in $PORTS; do
  if lsof -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "端口 $port 已被占用（IDEA 里的服务先停掉，或 bash bench/stop-nodes.sh）"
    exit 1
  fi
done

# 依次启动：一个节点就绪后再起下一个
# 聚合模式下服务间调用走 aggregation.remote-url，不依赖 Nacos 服务发现，关闭注册避免多节点同时注册失败
for port in $PORTS; do
  echo ">>> 启动节点 :$port"
  nohup java -Xms1g -Xmx1g -jar "$JAR" \
    --server.port="$port" \
    --ticket.bench.enabled=true \
    --spring.cloud.nacos.discovery.register-enabled=false \
    --mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl \
    > "$LOG_DIR/node-$port.log" 2>&1 &
  pid=$!
  echo $pid > "$LOG_DIR/node-$port.pid"
  printf ">>> 等待 :%s 就绪" "$port"
  ok=0
  for i in $(seq 1 90); do
    if check_node "http://127.0.0.1:$port"; then ok=1; break; fi
    if ! kill -0 "$pid" 2>/dev/null; then break; fi
    printf "."
    sleep 2
  done
  if [ "$ok" = 1 ]; then
    echo " ok"
  else
    echo " 失败，日志 $LOG_DIR/node-$port.log："
    grep -nE 'APPLICATION FAILED|Caused by|ERROR' "$LOG_DIR/node-$port.log" | head -15 || true
    bash "$BENCH_DIR/stop-nodes.sh"
    exit 1
  fi
done
