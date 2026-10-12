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

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.TrainStationPriceDO;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.TicketListDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 车票查询一级本地缓存（Caffeine）
 *
 * 只缓存「几乎不变」的静态数据：站点字典、站点->城市映射、城市间车次列表、区间票价；
 * 余票这种强实时数据不进本地缓存，始终读 Redis。
 *
 * 一致性：写入方修改数据后删除 Redis 并通过 {@link TicketQueryCacheBroadcaster} 广播失效各节点本地缓存，
 * expireAfterWrite 作为 Pub/Sub 消息丢失时的兜底。
 */
@Component
public class TicketQueryLocalCache {

    public static final String DICT = "dict";
    public static final String REGION_TRAIN = "region-train";
    public static final String PRICE = "price";

    /**
     * 站点字典 / 站点->城市映射，key 为 Redis Hash 的 key
     */
    private final Cache<String, Map<String, String>> dictCache;

    /**
     * 城市 -> 城市 的车次列表（模板对象，只读，返回前必须拷贝），key 为 Redis Hash 的 key
     * 空列表也会缓存，作为空值缓存防止没有车次的线路反复回源
     */
    private final Cache<String, List<TicketListDTO>> regionTrainCache;

    /**
     * 区间票价，key 为 Redis String 的 key
     */
    private final Cache<String, List<TrainStationPriceDO>> priceCache;

    public TicketQueryLocalCache(@Value("${ticket.query.local-cache.expire-seconds:300}") long expireSeconds,
                                 @Value("${ticket.query.local-cache.maximum-size:10000}") long maximumSize) {
        this.dictCache = Caffeine.newBuilder()
                .expireAfterWrite(expireSeconds, TimeUnit.SECONDS)
                .maximumSize(16)
                .recordStats()
                .build();
        this.regionTrainCache = Caffeine.newBuilder()
                .expireAfterWrite(expireSeconds, TimeUnit.SECONDS)
                .maximumSize(maximumSize)
                .recordStats()
                .build();
        this.priceCache = Caffeine.newBuilder()
                .expireAfterWrite(expireSeconds, TimeUnit.SECONDS)
                .maximumSize(maximumSize)
                .recordStats()
                .build();
    }

    /**
     * loader 返回 null 时不缓存（Redis 还没预热的情况）
     */
    public Map<String, String> getDict(String key, Supplier<Map<String, String>> loader) {
        return dictCache.get(key, k -> loader.get());
    }

    public List<TicketListDTO> getRegionTrains(String key, Supplier<List<TicketListDTO>> loader) {
        return regionTrainCache.get(key, k -> loader.get());
    }

    public Map<String, List<TrainStationPriceDO>> getPrices(Set<String> keys,
                                                            Function<Set<? extends String>, Map<String, List<TrainStationPriceDO>>> bulkLoader) {
        return priceCache.getAll(keys, bulkLoader);
    }

    public void invalidate(String cacheName, String key) {
        switch (cacheName) {
            case DICT -> dictCache.invalidate(key);
            case REGION_TRAIN -> regionTrainCache.invalidate(key);
            case PRICE -> priceCache.invalidate(key);
            default -> invalidateAll();
        }
    }

    public void invalidateAll() {
        dictCache.invalidateAll();
        regionTrainCache.invalidateAll();
        priceCache.invalidateAll();
    }

    /**
     * 命中统计（累计值），压测脚本前后各取一次做差计算命中率
     */
    public Map<String, Map<String, Long>> stats() {
        Map<String, Map<String, Long>> result = new LinkedHashMap<>();
        result.put(DICT, toMap(dictCache.stats(), dictCache.estimatedSize()));
        result.put(REGION_TRAIN, toMap(regionTrainCache.stats(), regionTrainCache.estimatedSize()));
        result.put(PRICE, toMap(priceCache.stats(), priceCache.estimatedSize()));
        return result;
    }

    /**
     * 三个缓存的命中/未命中总数：[hitCount, missCount]
     */
    public long[] totalHitAndMiss() {
        long hit = dictCache.stats().hitCount() + regionTrainCache.stats().hitCount() + priceCache.stats().hitCount();
        long miss = dictCache.stats().missCount() + regionTrainCache.stats().missCount() + priceCache.stats().missCount();
        return new long[]{hit, miss};
    }

    private Map<String, Long> toMap(CacheStats stats, long size) {
        Map<String, Long> map = new LinkedHashMap<>();
        map.put("hitCount", stats.hitCount());
        map.put("missCount", stats.missCount());
        map.put("loadSuccessCount", stats.loadSuccessCount());
        map.put("evictionCount", stats.evictionCount());
        map.put("estimatedSize", size);
        return map;
    }
}
