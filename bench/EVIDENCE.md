# 证据索引：每项成果 → 归属 / 代码 / 提交 / 测试 / 实测

分支：`perf/multilevel-cache`（基于 nageoffer/12306 `daf3a27`）。「上游已有」指原项目自带，「本人」指本分支新增或修改。
复核方式：`git show <提交>` 看改动；单测 `./mvnw -pl services/order-service,services/ticket-service -am test -Dskip.unit.tests=false`；实验 `bash bench/run-all.sh`、`bash bench/run-new.sh`，数据见 `bench/RESULTS.md`。

## 1. 查询多级缓存（本人）

| 项 | 归属 | 代码 | 提交 | 测试 | 实测（RESULTS.md 实验 1） |
|---|---|---|---|---|---|
| Caffeine L1（站点 / 车次 / 票价），余票一次 Pipeline | 本人 | `MultiLevelTicketQueryExecutor`、`TicketQueryLocalCache`、`TicketServiceImpl#pageListTicketQueryV3` | 168e61b | `MultiLevelTicketQueryExecutorTest`（7 个） | v2→v3：1,837→3,371 QPS，P99 136.7→100.4ms，L1 命中率 100% |
| 非法站点本地拦截、无车次线路空列表缓存 | 本人 | 同上 | 168e61b | 同上（5 次查询只回源 1 次；非法站点 0 次 Redis） | 无车次线路 207→24,363 QPS |
| 分布式锁 + 双重检查防击穿 | 上游已有，v3 复用 | `TicketServiceImpl#pageListTicketQueryV1` | — | — | — |
| 定位主要开销 | 本人（消融实验） | `TicketQueryBenchController` v3-static | 168e61b | — | 去掉余票读取 3,371→9,549 QPS |

## 2. 多节点本地缓存一致性（本人）

| 项 | 归属 | 代码 | 提交 | 测试 | 实测（实验 2a） |
|---|---|---|---|---|---|
| 本地失效 + Redis Pub/Sub 广播 + TTL 兜底 | 本人 | `TicketQueryCacheBroadcaster`、`TrainStationPriceAdminService` | 168e61b | `TicketQueryCacheBroadcasterTest`（3 个：跨节点失效、只记远端、广播丢失时 TTL 兜底） | 双节点 100 轮：端到端中位 11.5ms / P99 128ms，广播到达中位 0.98ms，99% 首读新值 |

## 3. 抢票并发正确性

| 项 | 归属 | 代码 | 提交 | 测试 | 实测（实验 2b） |
|---|---|---|---|---|---|
| 令牌桶 Lua、车次+席别两级锁 | 上游已有 | `TicketAvailabilityTokenBucket`、`purchaseTicketsV2` | — | — | 500 请求 / 库存 140：恰好 140，0 重复座位 |
| 售罄临界空车厢生成 `in group by` 非法 SQL | 本人修复 | `SeatServiceImpl#listSeatRemainingTicket` | e696c02 | `SeatServiceImplTest` | v1 其他失败 49→0 |
| 余票不足被误报为「车型暂未适配」 | 本人修复 | `TrainSeatTypeSelector#distributeSeats` | d667dac | `TrainSeatTypeSelectorTest`（2 个） | 同上 |
| 令牌桶冷启动 `tryLock()` 不等待 | 本人修复 | `TicketAvailabilityTokenBucket` | e696c02 | — | v2 其他失败 8→0 |

## 4. 业务一致性缺陷

| 项 | 归属 | 代码 | 提交 | 测试 | 状态 |
|---|---|---|---|---|---|
| 部分退票按整单金额退款 | 本人修复 | `TicketServiceImpl#commonTicketRefund` | bd0d4f1（测试，修复前 2/3 失败：退 2,313 元实际 4,040 元）→ 7f91db3（修复） | `TicketServiceImplRefundTest`（3 个） | 已验证（单测） |
| 扣令牌后下单失败，令牌与 Redis 余票不恢复 | 本人修复 | `TicketServiceImpl#purchaseTicketsV2`、`AbstractTrainPurchaseTicketTemplate#executeResp` | 8b7cd5b | 实验 5（注入 20% 失败，补偿开 / 关对照） | **待实测** |
| 并发重复关单可能重复回滚（读代码发现，未复现） | 本人加固 | `OrderServiceImpl#cancelTickOrder` UPDATE 加状态条件 | d9419b2 | 实验 4b（重复投递） | **待实测** |

## 5. 异步关单链路

| 项 | 归属 | 代码 | 提交 | 状态 |
|---|---|---|---|---|
| RocketMQ 延时关单、回滚座位 / 余票 / 令牌、`@Idempotent` 消费幂等 | 上游已有 | `DelayCloseOrderConsumer` | — | — |
| 关单时效、重复投递幂等实验 | 本人 | `run-close-bench.sh`、`DelayCloseBenchController` | d9419b2 | **待实测**（实验 4） |

## 6. 分库分表

| 项 | 归属 | 代码 | 提交 | 测试 | 实测 |
|---|---|---|---|---|---|
| 订单基因法分 16 表、手机号 / 邮箱路由表 | 上游已有 | `OrderCommonTableComplexAlgorithm`、`t_user_phone/mail` | — | — | — |
| 基因位未补零导致按订单号路由错表 | 本人修复 | `OrderIdGeneratorManager#buildOrderSn` | 168e61b | `OrderCommonTableComplexAlgorithmTest`（3 个，10 万样本） | 修复前 8.89% 错表，修复后 0 |

## 已知局限（未覆盖）

- 补偿逻辑自身失败（如归还令牌时 Redis 不可用）只记录错误日志，没有重试或对账任务。
- 多席别并行选座（线程池路径）时，余票扣减不在事务线程内，仍是立即扣减。
- 所有性能数据为单机（Apple M4）测量，节点、中间件、压测机在同一台机器上。
- 基因法单测验证的是 19 位雪花量级 userId；userId 少于 6 位时路由仍不一致（上游设计限制）。
