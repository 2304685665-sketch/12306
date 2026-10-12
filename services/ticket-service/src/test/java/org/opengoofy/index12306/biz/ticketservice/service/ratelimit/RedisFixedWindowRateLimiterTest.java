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
package org.opengoofy.index12306.biz.ticketservice.service.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.framework.starter.cache.DistributedCache;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Lua 限流器：正常放行 / 拒绝，以及 Redis 异常时的降级策略（默认放行，可配置为拒绝）
 */
class RedisFixedWindowRateLimiterTest {

    private StringRedisTemplate template;
    private RedisFixedWindowRateLimiter limiter;

    @BeforeEach
    void setUp() {
        template = mock(StringRedisTemplate.class);
        DistributedCache distributedCache = mock(DistributedCache.class);
        when(distributedCache.getInstance()).thenReturn(template);
        limiter = new RedisFixedWindowRateLimiter(distributedCache);
    }

    @Test
    void luaReturnsOneMeansAcquired() {
        when(template.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(1L);
        assertThat(limiter.tryAcquire("u1", 50, 1000)).isTrue();
        verify(template).execute(any(RedisScript.class), eq(List.of(RedisFixedWindowRateLimiter.KEY_PREFIX + "u1")), eq("50"), eq("1000"));
    }

    @Test
    void luaReturnsZeroMeansRejected() {
        when(template.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(0L);
        assertThat(limiter.tryAcquire("u1", 50, 1000)).isFalse();
    }

    @Test
    void redisFailureFailsOpenByDefault() {
        when(template.execute(any(RedisScript.class), anyList(), any(), any()))
                .thenThrow(new RedisConnectionFailureException("Unable to connect to Redis"));
        assertThat(limiter.tryAcquire("u1", 50, 1000)).isTrue();
    }

    @Test
    void redisFailureFailsClosedWhenConfigured() throws Exception {
        Field failOpen = RedisFixedWindowRateLimiter.class.getDeclaredField("failOpen");
        failOpen.setAccessible(true);
        failOpen.setBoolean(limiter, false);
        when(template.execute(any(RedisScript.class), anyList(), any(), any()))
                .thenThrow(new RedisConnectionFailureException("Unable to connect to Redis"));
        assertThat(limiter.tryAcquire("u1", 50, 1000)).isFalse();
    }
}
