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

import lombok.RequiredArgsConstructor;
import org.opengoofy.index12306.framework.starter.cache.DistributedCache;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Redis 的固定窗口限流
 *
 * <pre>
 * tryAcquire      ：Lua 脚本把「自增 + 判断是否超限」合成一个原子操作，多节点并发下放行数严格不超过上限
 * tryAcquireNaive ：对照实现，先 GET 判断再 INCR，两步之间存在竞态，并发下会超发（仅用于压测对照）
 * </pre>
 */
@Component
@RequiredArgsConstructor
public class RedisFixedWindowRateLimiter {

    public static final String KEY_PREFIX = "index12306-ticket-service:rate-limit:";

    private static final DefaultRedisScript<Long> SCRIPT = buildScript();

    private final DistributedCache distributedCache;

    public boolean tryAcquire(String key, long limit, long windowMillis) {
        Long result = template().execute(SCRIPT, List.of(KEY_PREFIX + key), String.valueOf(limit), String.valueOf(windowMillis));
        return result != null && result == 1L;
    }

    public boolean tryAcquireNaive(String key, long limit, long windowMillis) {
        StringRedisTemplate template = template();
        String actualKey = KEY_PREFIX + key;
        long current = Optional.ofNullable(template.opsForValue().get(actualKey)).map(Long::parseLong).orElse(0L);
        if (current >= limit) {
            return false;
        }
        Long incremented = template.opsForValue().increment(actualKey);
        if (incremented != null && incremented == 1L) {
            template.expire(actualKey, windowMillis, TimeUnit.MILLISECONDS);
        }
        return true;
    }

    private StringRedisTemplate template() {
        return (StringRedisTemplate) distributedCache.getInstance();
    }

    private static DefaultRedisScript<Long> buildScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/ticket_query_rate_limit.lua")));
        script.setResultType(Long.class);
        return script;
    }
}
