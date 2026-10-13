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
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.SeatMapper;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.TrainMapper;
import org.opengoofy.index12306.biz.ticketservice.service.TrainStationService;
import org.opengoofy.index12306.framework.starter.cache.DistributedCache;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 余票缓存加载的双重检查：排队等锁的请求拿到锁时，缓存往往已被前一个请求构建好，
 * 此时必须返回缓存中的余票，而不是空结果（空结果会被调用方当作余票 0 而拒绝购票）
 */
class SeatMarginCacheLoaderTest {

    private static final String KEY = "index12306-ticket-service:train_station_remaining_ticket:2_北京南_杭州东";

    private HashOperations<String, Object, Object> hashOperations;
    private TrainMapper trainMapper;
    private SeatMarginCacheLoader loader;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        hashOperations = mock(HashOperations.class);
        when(template.opsForHash()).thenReturn((HashOperations) hashOperations);
        DistributedCache distributedCache = mock(DistributedCache.class);
        when(distributedCache.getInstance()).thenReturn(template);
        RedissonClient redissonClient = mock(RedissonClient.class);
        when(redissonClient.getLock(anyString())).thenReturn(mock(RLock.class));
        trainMapper = mock(TrainMapper.class);
        loader = new SeatMarginCacheLoader(trainMapper, mock(SeatMapper.class), distributedCache, redissonClient, mock(TrainStationService.class));
    }

    @Test
    void cacheBuiltByAnotherRequestWhileWaitingForLockIsReturned() {
        // 拿到锁后的二次检查：缓存已存在
        when(hashOperations.get(KEY, "1")).thenReturn("140");
        Map<Object, Object> cached = new LinkedHashMap<>();
        cached.put("0", "30");
        cached.put("1", "140");
        cached.put("2", "810");
        when(hashOperations.entries(KEY)).thenReturn(cached);

        Map<String, String> result = loader.load("2", "1", "北京南", "杭州东");

        assertThat(result).as("应返回缓存中的余票，而不是空结果").containsEntry("1", "140");
        verifyNoInteractions(trainMapper);
    }
}
