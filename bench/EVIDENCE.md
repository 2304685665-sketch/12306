# 证据索引：每项成果 → 归属 / 代码 / 提交 / 测试 / 实测

分支：`perf/multilevel-cache`（基于 nageoffer/12306 `daf3a27`）。「上游已有」指原项目自带，「本人」指本分支新增或修改。
单测共 31 个（ticket-service 28、order-service 3），在 JDK 17 下全部通过。

复核方式：`git show <提交>` 看改动；单测 `./mvnw -pl services/order-service,services/ticket-service -am test -Dskip.unit.tests=false`；实验 `bash bench/run-all.sh`、`bash bench/run-new.sh`，数据见 `bench/RESULTS.md`。

## 1. 查询多级缓存（本人）

| 项 | 归属 | 代码 | 提交 | 测试 | 实测（RESULTS.md 实验 1） |
|---|---|---|---|---|---|
| Caffeine L1（站点 / 车次 / 票价），余票一次 Pipeline | 本人 | `MultiLevelTicketQueryExecutor`、`TicketQueryLocalCache`、`TicketServiceImpl#pageListTicketQueryV3` | 168e61b | `MultiLevelTicketQueryExecutorTest`（7 个） | 3 轮中位数 v2→v3：1,893→5,301 QPS（+180%），P99 106.2→38.6ms，L1 命中率 100% |
| 非法站点本地拦截、无车次线路空列表缓存 | 本人 | 同上 | 168e61b | 同上（5 次查询只回源 1 次；非法站点 0 次 Redis） | 3 轮中位数：无车次线路 257→17,902 QPS |
| 分布式锁 + 双重检查防击穿 | 上游已有，v3 复用 | `TicketServiceImpl#pageListTicketQueryV1` | — | — | — |
| 定位主要开销 | 本人（消融实验） | `TicketQueryBenchController` v3-static | 168e61b | — | 3 轮中位数：去掉余票读取 5,301→13,535 QPS |

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
| 余票缓存加载二次检查命中时返回空结果，冷启动并发误判「无余票」 | 本人修复 | `SeatMarginCacheLoader#load` | 63e0762（测试，修复前失败）→ 4c2671e（修复） | `SeatMarginCacheLoaderTest` | 修复前：实验 5 中 20 并发有 19 个请求在有票时被拒；修复后：冷启动 50 并发 v1 / v2 各 3 轮均 50 成功、0 误拒（实验 7），实验 5 重跑误拒消失 |

## 3b. 自研 Redis Lua 原子限流（本人）

| 项 | 归属 | 代码 | 测试 / 实验 | 状态 |
|---|---|---|---|---|
| 固定窗口限流：Lua 原子「自增 + 判断」，查票接口按用户限流（默认关闭，`ticket.query.rate-limit.permits-per-second`） | 本人 | `RedisFixedWindowRateLimiter`、`lua/ticket_query_rate_limit.lua`、`TicketController#pageListTicketQuery` | 脚本级：真实 Redis，50 并发 × 500 次、上限 50，3 轮 Lua 均放行 50，非原子 GET+INCR 放行 59 / 60 / 61；应用级（实验 6，经两个应用节点交替请求，3 轮）：Lua 均放行 50、Redis 计数 500；非原子放行 69 / 98 / 76；窗口重置 5/10 → 过期后 5/10 | 脚本级、应用级（压测接口）已验证；真实 `/ticket/query`：开启每秒 20 次时双节点 300 次请求合计放行 20（计数共享），关闭时 300 次全部正常 |
| Redis 异常降级：默认 fail-open（放行并告警），`ticket.rate-limit.fail-open=false` 时 fail-closed | 本人 | `RedisFixedWindowRateLimiter#tryAcquire` | `RedisFixedWindowRateLimiterTest`（4 个：放行 / 拒绝 / 异常放行 / 异常拒绝） | 已验证（单测） |
| 限流开关：关闭时完全不触达 Redis；超限时不进入查询 | 本人 | `TicketController#pageListTicketQuery` | `TicketControllerRateLimitTest`（3 个） | 已验证（单测） |

选择 fail-open 的理由：查票是只读接口，限流是保护性措施，Redis 故障时宁可短时失去限流，也不让全部查票失败；购票链路的令牌桶仍由上游逻辑兜底。

## 4. 业务一致性缺陷

| 项 | 归属 | 代码 | 提交 | 测试 | 状态 |
|---|---|---|---|---|---|
| 部分退票按整单金额退款 | 本人修复 | `TicketServiceImpl#commonTicketRefund` | bd0d4f1（测试，修复前 2/3 失败：退 2,313 元实际 4,040 元）→ 7f91db3（修复） | `TicketServiceImplRefundTest`（3 个） | 已验证（单测） |
| 扣令牌后下单失败，令牌与 Redis 余票不恢复 | 本人修复 | `TicketServiceImpl#purchaseTicketsV2`、`AbstractTrainPurchaseTicketTemplate#executeResp` | 8b7cd5b | `TicketServiceImplCompensationTest`（4 个：失败归还、补偿自身失败不覆盖原异常、成功不归还、关闭补偿保持上游行为）；实验 5（注入 20% 失败）：关闭补偿时令牌与余票各少计 18，开启后 0 | 已验证 |
| 并发重复关单可能重复回滚（读代码发现，未复现） | 本人加固 | `OrderServiceImpl#cancelTickOrder` UPDATE 加状态条件 | d9419b2 | 实验 4b：MQ 重投与业务重复各 100 次投递，均只回滚 20 次，令牌 / 余票 140 | 已验证 |

## 5. 异步关单链路

| 项 | 归属 | 代码 | 提交 | 状态 |
|---|---|---|---|---|
| RocketMQ 延时关单、回滚座位 / 余票 / 令牌、`@Idempotent` 消费幂等 | 上游已有 | `DelayCloseOrderConsumer` | — | — |
| 关单时效实验（4a） | 本人 | `run-close-bench.sh` | d9419b2 | 已实测：50 单延迟 1s 关单，延迟到期后回滚完成 P50 0.54s / P99 2.65s / 最大 6.4s；令牌、余票缓存、座位全部精确恢复到 140 |
| 重复投递幂等实验（4b） | 本人 | `DelayCloseBenchController` | d9419b2 | 已实测：100 次投递只回滚 20 次 |

## 6. 分库分表

| 项 | 归属 | 代码 | 提交 | 测试 | 实测 |
|---|---|---|---|---|---|
| 订单基因法分 16 表、手机号 / 邮箱路由表 | 上游已有 | `OrderCommonTableComplexAlgorithm`、`t_user_phone/mail` | — | — | — |
| 基因位未补零导致按订单号路由错表 | 本人修复 | `OrderIdGeneratorManager#buildOrderSn` | 168e61b | `OrderCommonTableComplexAlgorithmTest`（3 个，10 万样本） | 修复前 8.89% 错表，修复后 0 |

## 已知局限（未覆盖）

- 补偿逻辑自身失败（如归还令牌时 Redis 不可用）：已有单测保证不会覆盖原业务异常，但只记录错误日志，没有重试或对账任务；令牌桶会在「令牌为空」时由上游逻辑按 DB 余量刷新，属于延迟兜底而非即时修复。
- 限流 fail-open 只在单测中模拟 Redis 异常，没有在真实环境里停掉 Redis 做故障演练。
- 多席别并行选座（线程池路径）时，余票扣减不在事务线程内，仍是立即扣减。
- 实验 1 为 3 轮重复，波动范围较大（C 组 QPS 5,179～7,188），样本量仍小。
- 所有性能数据为单机（Apple M4）测量，节点、中间件、压测机在同一台机器上。
- 基因法单测验证的是 19 位雪花量级 userId；userId 少于 6 位时路由仍不一致（上游设计限制）。
