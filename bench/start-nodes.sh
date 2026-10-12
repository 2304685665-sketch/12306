#!/usr/bin/env bash
# 打包并启动 aggregation-service 节点（默认两个：9005 / 9006），开启压测接口、关闭 MyBatis SQL 打印
# 用法：bash bench/start-nodes.sh            # 打包 + 启动 9005、9006
#       SKIP_BUILD=1 bash bench/start-nodes.sh
#       PORTS="9005" bash bench/start-nodes.sh  # 只起一个节点
source "$(dirname "$0")/common.sh"
require java
require docker

JAVA_MAJOR="$(java -version 2>&1 | awk -F'"' '/version/{print $2}' | cut -d. -f1)"
if [ "${JAVA_MAJOR:-0}" -lt 17 ]; then
  echo "当前 java 版本为 $JAVA_MAJOR，需要 17+（可以 export JAVA_HOME=\$(/usr/libexec/java_home -v 17)）"
  exit 1
fi

RUNNING="$(docker ps --format '{{.Names}}')"
for c in "$MYSQL_CONTAINER" "$REDIS_CONTAINER" 12306-nacos 12306-rmq-namesrv 12306-rmq-broker; do
  if ! echo "$RUNNING" | grep -x "$c" >/dev/null; then echo "容器 $c 没在运行，先 docker compose up -d"; exit 1; fi
done

cd "$ROOT_DIR"
if [ "${SKIP_BUILD:-0}" != "1" ]; then
  echo ">>> 打包 aggregation-service（首次较慢）"
  ./mvnw -q -DskipTests -pl services/aggregation-service -am package
fi
JAR="$ROOT_DIR/services/aggregation-service/target/index12306-aggregation-service.jar"
[ -f "$JAR" ] || { echo "找不到 $JAR"; exit 1; }

PORTS="${PORTS:-9005 9006}"
for port in $PORTS; do
  if lsof -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "端口 $port 已被占用（IDEA 里的服务先停掉，或 bash bench/stop-nodes.sh）"
    exit 1
  fi
  echo ">>> 启动节点 :$port"
  nohup java -Xms1g -Xmx1g -jar "$JAR" \
    --server.port="$port" \
    --ticket.bench.enabled=true \
    --mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl \
    > "$LOG_DIR/node-$port.log" 2>&1 &
  echo $! > "$LOG_DIR/node-$port.pid"
done

for port in $PORTS; do
  printf ">>> 等待 :%s 就绪" "$port"
  for i in $(seq 1 120); do
    if check_node "http://127.0.0.1:$port"; then echo " ok"; break; fi
    printf "."
    sleep 2
    if [ "$i" = 120 ]; then echo " 超时，查看 $LOG_DIR/node-$port.log"; exit 1; fi
  done
done
