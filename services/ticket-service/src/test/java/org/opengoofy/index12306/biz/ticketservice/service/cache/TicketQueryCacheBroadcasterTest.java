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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.TrainStationPriceDO;
import org.opengoofy.index12306.framework.starter.cache.DistributedCache;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.api.listener.MessageListener;
import org.redisson.client.codec.Codec;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 本地缓存失效广播 + TTL 兜底单元测试：用内存里的假 Topic 模拟 Redis Pub/Sub，两个 Broadcaster 模拟两个节点
 */
class TicketQueryCacheBroadcasterTest {

    private static final String PRICE_KEY = "index12306-ticket-service:train_station_price:2_北京南_杭州东";

    private final List<MessageListener<String>> subscribers = new CopyOnWriteArrayList<>();
    private RedissonClient redissonClient;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        RTopic topic = mock(RTopic.class);
        when(topic.addListener(eq(String.class), any(MessageListener.class))).thenAnswer(inv -> {
            subscribers.add(inv.getArgument(1));
            return subscribers.size();
        });
        when(topic.publish(any())).thenAnswer(inv -> {
            for (MessageListener<String> each : subscribers) {
                each.onMessage(TicketQueryCacheBroadcaster.TOPIC, inv.getArgument(0));
            }
            return (long) subscribers.size();
        });
        redissonClient = mock(RedissonClient.class);
        when(redissonClient.getTopic(eq(TicketQueryCacheBroadcaster.TOPIC), any(Codec.class))).thenReturn(topic);
    }

    @Test
    void evictOnNodeAInvalidatesLocalCacheOnNodeB() {
        TicketQueryLocalCache cacheA = new TicketQueryLocalCache(300, 1000);
        TicketQueryLocalCache cacheB = new TicketQueryLocalCache(300, 1000);
        TicketQueryCacheBroadcaster nodeA = node(cacheA, mock(DistributedCache.class), false);
        TicketQueryCacheBroadcaster nodeB = node(cacheB, mock(DistributedCache.class), false);
        AtomicInteger loadsA = new AtomicInteger();
        AtomicInteger loadsB = new AtomicInteger();
        load(cacheA, loadsA);
        load(cacheB, loadsB);
        load(cacheA, loadsA);
        load(cacheB, loadsB);
        assertThat(loadsA).hasValue(1);
        assertThat(loadsB).hasValue(1);

        nodeA.evictAndBroadcast(TicketQueryLocalCache.PRICE, PRICE_KEY);

        load(cacheA, loadsA);
        load(cacheB, loadsB);
        assertThat(loadsA).as("节点 A 本地失效后重新加载").hasValue(2);
        assertThat(loadsB).as("节点 B 收到广播后重新加载").hasValue(2);
        assertThat(nodeA.getNodeId()).isNotEqualTo(nodeB.getNodeId());
    }

    @Test
    @SuppressWarnings("unchecked")
    void benchModeRecordsPropagationLatencyOnlyOnRemoteNodes() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        ListOperations<String, String> listOps = mock(ListOperations.class);
        when(template.opsForList()).thenReturn(listOps);
        DistributedCache distributedCache = mock(DistributedCache.class);
        when(distributedCache.getInstance()).thenReturn(template);
        TicketQueryCacheBroadcaster nodeA = node(new TicketQueryLocalCache(300, 1000), distributedCache, true);
        node(new TicketQueryLocalCache(300, 1000), distributedCache, true);
        node(new TicketQueryLocalCache(300, 1000), distributedCache, true);

        nodeA.evictAndBroadcast(TicketQueryLocalCache.PRICE, PRICE_KEY);

        // 3 个节点订阅，发布方自己跳过，只有另外 2 个节点记录耗时
        verify(listOps, times(2)).leftPush(eq(TicketQueryCacheBroadcaster.BENCH_LATENCY_KEY), anyString());
    }

    @Test
    void ttlExpiresEntryWhenBroadcastIsLost() throws InterruptedException {
        TicketQueryLocalCache cache = new TicketQueryLocalCache(1, 1000);
        AtomicInteger loads = new AtomicInteger();
        load(cache, loads);
        load(cache, loads);
        assertThat(loads).hasValue(1);
        Thread.sleep(1200);
        load(cache, loads);
        assertThat(loads).as("没有收到广播时，TTL 到期后也会重新加载").hasValue(2);
    }

    private TicketQueryCacheBroadcaster node(TicketQueryLocalCache cache, DistributedCache distributedCache, boolean benchEnabled) {
        TicketQueryCacheBroadcaster broadcaster = new TicketQueryCacheBroadcaster(redissonClient, cache, distributedCache);
        ReflectionTestUtils.setField(broadcaster, "benchEnabled", benchEnabled);
        broadcaster.afterPropertiesSet();
        return broadcaster;
    }

    private static void load(TicketQueryLocalCache cache, AtomicInteger counter) {
        Map<String, List<TrainStationPriceDO>> result = cache.getPrices(Set.of(PRICE_KEY), keys -> {
            counter.incrementAndGet();
            return Map.of(PRICE_KEY, Collections.emptyList());
        });
        assertThat(result).containsKey(PRICE_KEY);
    }
}
