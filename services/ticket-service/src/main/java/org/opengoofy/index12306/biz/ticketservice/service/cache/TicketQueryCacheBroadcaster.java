/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.opengoofy.index12306.biz.ticketservice.service.cache;

import com.alibaba.fastjson2.JSON;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opengoofy.index12306.framework.starter.cache.DistributedCache;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * 本地缓存失效广播：本地失效 + Redis Pub/Sub 广播 + TTL 兜底
 *
 * Pub/Sub 是 at-most-once，节点断连期间的消息会丢，所以本地缓存必须同时设置 expireAfterWrite 兜底，
 * 保证最坏情况下 TTL 到期后也能最终一致。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketQueryCacheBroadcaster implements InitializingBean {

    public static final String TOPIC = "index12306-ticket-service:topic:query-local-cache-invalidate";

    /**
     * 压测开关打开时，各节点把「消息发布 -> 本节点完成失效」的耗时（微秒）写到这个 List 里
     */
    public static final String BENCH_LATENCY_KEY = "index12306-ticket-service:bench:local-cache-invalidate-latency-us";

    private final RedissonClient redissonClient;
    private final TicketQueryLocalCache ticketQueryLocalCache;
    private final DistributedCache distributedCache;

    @Value("${ticket.bench.enabled:false}")
    private boolean benchEnabled;

    private final String nodeId = UUID.randomUUID().toString().substring(0, 8);

    private RTopic topic;

    @Override
    public void afterPropertiesSet() {
        topic = redissonClient.getTopic(TOPIC, StringCodec.INSTANCE);
        topic.addListener(String.class, (channel, message) -> onMessage(message));
    }

    /**
     * 先失效本节点，再广播给其他节点
     */
    public void evictAndBroadcast(String cacheName, String key) {
        ticketQueryLocalCache.invalidate(cacheName, key);
        InvalidateMessage message = new InvalidateMessage(cacheName, key, nowMicros(), nodeId);
        topic.publish(JSON.toJSONString(message));
    }

    private void onMessage(String raw) {
        InvalidateMessage message = JSON.parseObject(raw, InvalidateMessage.class);
        if (nodeId.equals(message.getOrigin())) {
            return;
        }
        ticketQueryLocalCache.invalidate(message.getCacheName(), message.getKey());
        if (benchEnabled) {
            long costMicros = nowMicros() - message.getPublishMicros();
            try {
                StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
                stringRedisTemplate.opsForList().leftPush(BENCH_LATENCY_KEY, nodeId + ":" + costMicros);
            } catch (Throwable ex) {
                log.warn("记录本地缓存失效耗时失败", ex);
            }
        }
        log.debug("[本地缓存失效] 节点：{} 收到广播 cache={} key={}", nodeId, message.getCacheName(), message.getKey());
    }

    public String getNodeId() {
        return nodeId;
    }

    private static long nowMicros() {
        return ChronoUnit.MICROS.between(Instant.EPOCH, Instant.now());
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InvalidateMessage {

        private String cacheName;

        private String key;

        private Long publishMicros;

        private String origin;
    }
}
