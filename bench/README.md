# 压测与对照实验

所有实验都在本机跑：中间件用 `docker compose`，aggregation-service 用 `java -jar` 起 1~2 个节点。

## 前置

```bash
docker compose up -d          # MySQL(3307) / Redis / Nacos / RocketMQ
brew install k6
java -version                 # 需要 17+，否则 export JAVA_HOME=$(/usr/libexec/java_home -v 17)
```

IDEA 里如果正在跑 aggregation-service（9005），先停掉。

## 一键执行

```bash
bash bench/run-all.sh
```

大约 10 分钟。结束后把 `bench/results/ALL-*.txt` 的内容发回来。

## 单独执行

| 脚本 | 内容 |
| --- | --- |
| `start-nodes.sh` / `stop-nodes.sh` | 打包并启动 9005、9006 两个节点（开启 `ticket.bench.enabled`，关闭 SQL 打印） |
| `run-query-bench.sh` | 实验 1：查询 v1 / v2 / v3 / v3-static 四组对照 + 无车次线路防穿透两组，统计 QPS、P99、每请求 Redis 命令数、L1 命中率 |
| `run-consistency-bench.sh` | 实验 2a：节点 A 调价后，节点 B 读到新价格的端到端耗时 + Pub/Sub 广播到达耗时 |
| `run-oversell-bench.sh` | 实验 2b：500 个用户经两个节点并发抢 140 张一等座，v1 / v2 对照，核对 DB 车票数、重复座位、令牌桶余量 |
| `reset-purchase-data.sh` | 还原 G39 座位、车票、订单与缓存 |
| `run-shard-test.sh` | 实验 3：订单基因法分片路由一致性单测 |

可调参数：`VUS`、`DURATION`、`USERS`、`ROUNDS`、`NODE_A`、`NODE_B`、`MYSQL_CONTAINER`、`REDIS_CONTAINER`。
