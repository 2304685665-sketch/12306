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


package org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.query;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.TrainStationPriceDO;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.TrainStationPriceMapper;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.SeatClassDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.TicketListDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.TicketPageQueryReqDTO;
import org.opengoofy.index12306.biz.ticketservice.service.cache.SeatMarginCacheLoader;
import org.opengoofy.index12306.biz.ticketservice.service.cache.TicketQueryLocalCache;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.filter.query.TrainTicketQueryParamBaseVerifyChainFilter;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.filter.query.TrainTicketQueryParamNotNullChainFilter;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.filter.query.TrainTicketQueryParamVerifyChainFilter;
import org.opengoofy.index12306.biz.ticketservice.toolkit.TimeStringComparator;
import org.opengoofy.index12306.framework.starter.cache.DistributedCache;
import org.opengoofy.index12306.framework.starter.convention.exception.ClientException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.opengoofy.index12306.biz.ticketservice.common.constant.Index12306Constant.ADVANCE_TICKET_DAY;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.QUERY_ALL_REGION_LIST;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.REGION_TRAIN_STATION;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.REGION_TRAIN_STATION_MAPPING;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_STATION_PRICE;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_STATION_REMAINING_TICKET;

/**
 * 车票查询 v3：Caffeine 一级缓存 + Redis 二级缓存
 *
 * <pre>
 * 站点校验 / 站点->城市 / 城市间车次 / 区间票价  -> L1 命中直接返回，未命中回 Redis（Redis 也没有时复用 v1 的加锁 + 双重检查构建逻辑）
 * 余票                                       -> 强实时，始终走 Redis，所有车次所有席别一次 Pipeline 取回
 * </pre>
 *
 * 一次查询的 Redis 往返从 v1 的「2 + 1 + N(车次) + Σ席别」次降到 1 次（L1 命中时）。
 */
@Component
@RequiredArgsConstructor
public class MultiLevelTicketQueryExecutor {

    private final DistributedCache distributedCache;
    private final TicketQueryLocalCache ticketQueryLocalCache;
    private final SeatMarginCacheLoader seatMarginCacheLoader;
    private final TrainStationPriceMapper trainStationPriceMapper;
    private final TrainTicketQueryParamNotNullChainFilter notNullChainFilter;
    private final TrainTicketQueryParamBaseVerifyChainFilter baseVerifyChainFilter;
    private final TrainTicketQueryParamVerifyChainFilter regionVerifyChainFilter;

    @Value("${framework.cache.redis.prefix:}")
    private String cacheRedisPrefix;

    /**
     * @param requestParam      查询参数
     * @param withRemaining     是否读取余票（压测定位开销用，正常请求恒为 true）
     * @param redisWarmUp       Redis 未预热时的构建逻辑（复用 v1：分布式锁 + 双重检查回源 DB 并写 Redis）
     */
    public List<TicketListDTO> query(TicketPageQueryReqDTO requestParam, boolean withRemaining, Runnable redisWarmUp) {
        notNullChainFilter.handler(requestParam);
        baseVerifyChainFilter.handler(requestParam);
        String fromStation = requestParam.getFromStation();
        String toStation = requestParam.getToStation();

        // 1. 站点合法性校验：本地字典直接判断，非法站点不再打到 Redis
        Map<String, String> allRegionStation = ticketQueryLocalCache.getDict(QUERY_ALL_REGION_LIST, () -> loadHash(QUERY_ALL_REGION_LIST, () -> regionVerifyChainFilter.handler(requestParam)));
        if (allRegionStation == null || !allRegionStation.containsKey(fromStation) || !allRegionStation.containsKey(toStation)) {
            throw new ClientException("出发地或目的地不存在");
        }

        // 2. 站点 -> 城市
        Map<String, String> stationRegionMapping = ticketQueryLocalCache.getDict(REGION_TRAIN_STATION_MAPPING, () -> loadHash(REGION_TRAIN_STATION_MAPPING, redisWarmUp));
        if (stationRegionMapping == null || !stationRegionMapping.containsKey(fromStation) || !stationRegionMapping.containsKey(toStation)) {
            // 新增站点后本地字典可能是旧的，失效后重载一次
            ticketQueryLocalCache.invalidate(TicketQueryLocalCache.DICT, REGION_TRAIN_STATION_MAPPING);
            redisWarmUp.run();
            stationRegionMapping = ticketQueryLocalCache.getDict(REGION_TRAIN_STATION_MAPPING, () -> loadHash(REGION_TRAIN_STATION_MAPPING, redisWarmUp));
            if (stationRegionMapping == null || !stationRegionMapping.containsKey(fromStation) || !stationRegionMapping.containsKey(toStation)) {
                throw new ClientException("出发地或目的地不存在");
            }
        }

        // 3. 城市间车次列表（空列表同样缓存，防止无车次线路反复加锁回源 DB）
        String regionTrainKey = String.format(REGION_TRAIN_STATION, stationRegionMapping.get(fromStation), stationRegionMapping.get(toStation));
        List<TicketListDTO> trainTemplates = ticketQueryLocalCache.getRegionTrains(regionTrainKey, () -> loadRegionTrains(regionTrainKey, redisWarmUp));
        if (trainTemplates.isEmpty()) {
            return Collections.emptyList();
        }

        // 4. 区间票价：L1 批量取，缺失的 key 用一次 Pipeline 从 Redis 补齐
        Map<String, TicketListDTO> priceKeyMap = new LinkedHashMap<>();
        for (TicketListDTO each : trainTemplates) {
            priceKeyMap.put(String.format(TRAIN_STATION_PRICE, each.getTrainId(), each.getDeparture(), each.getArrival()), each);
        }
        Map<String, List<TrainStationPriceDO>> priceMap = ticketQueryLocalCache.getPrices(new LinkedHashSet<>(priceKeyMap.keySet()), missingKeys -> loadPrices(missingKeys, priceKeyMap));

        // 5. 余票：所有车次所有席别一次 Pipeline
        List<Object> remainingResults = Collections.emptyList();
        List<String[]> remainingIndex = new ArrayList<>();
        for (TicketListDTO each : trainTemplates) {
            String keySuffix = StrUtil.join("_", each.getTrainId(), each.getDeparture(), each.getArrival());
            for (TrainStationPriceDO item : priceMap.getOrDefault(String.format(TRAIN_STATION_PRICE, each.getTrainId(), each.getDeparture(), each.getArrival()), Collections.emptyList())) {
                remainingIndex.add(new String[]{keySuffix, String.valueOf(item.getSeatType())});
            }
        }
        if (withRemaining && !remainingIndex.isEmpty()) {
            StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
            remainingResults = stringRedisTemplate.executePipelined((RedisCallback<String>) connection -> {
                for (String[] each : remainingIndex) {
                    connection.hashCommands().hGet((cacheRedisPrefix + TRAIN_STATION_REMAINING_TICKET + each[0]).getBytes(), each[1].getBytes());
                }
                return null;
            });
        }

        // 6. 拷贝模板对象并组装席别信息（模板是多线程共享的，绝不能直接修改）
        List<TicketListDTO> results = new ArrayList<>(trainTemplates.size());
        int cursor = 0;
        for (TicketListDTO template : trainTemplates) {
            TicketListDTO each = copyOf(template);
            List<TrainStationPriceDO> prices = priceMap.getOrDefault(String.format(TRAIN_STATION_PRICE, each.getTrainId(), each.getDeparture(), each.getArrival()), Collections.emptyList());
            List<SeatClassDTO> seatClassList = new ArrayList<>(prices.size());
            for (TrainStationPriceDO item : prices) {
                int quantity = 0;
                if (withRemaining) {
                    Object quantityObj = cursor < remainingResults.size() ? remainingResults.get(cursor) : null;
                    quantity = quantityObj != null
                            ? Integer.parseInt(quantityObj.toString())
                            : loadSeatMargin(each, item);
                }
                cursor++;
                seatClassList.add(new SeatClassDTO(item.getSeatType(), quantity, new BigDecimal(item.getPrice()).divide(new BigDecimal("100"), 1, RoundingMode.HALF_UP), false));
            }
            each.setSeatClassList(seatClassList);
            results.add(each);
        }
        return results;
    }

    private Map<String, String> loadHash(String key, Runnable warmUp) {
        StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
        Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(key);
        if (entries.isEmpty()) {
            warmUp.run();
            entries = stringRedisTemplate.opsForHash().entries(key);
        }
        if (entries.isEmpty()) {
            // 返回 null 不写入本地缓存，下次继续尝试
            return null;
        }
        Map<String, String> result = new HashMap<>(entries.size() * 2);
        entries.forEach((k, v) -> result.put(String.valueOf(k), String.valueOf(v)));
        return Collections.unmodifiableMap(result);
    }

    private List<TicketListDTO> loadRegionTrains(String key, Runnable warmUp) {
        StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
        Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(key);
        if (entries.isEmpty()) {
            warmUp.run();
            entries = stringRedisTemplate.opsForHash().entries(key);
        }
        List<TicketListDTO> result = entries.values().stream()
                .map(each -> JSON.parseObject(each.toString(), TicketListDTO.class))
                .sorted(new TimeStringComparator())
                .toList();
        return Collections.unmodifiableList(result);
    }

    private Map<String, List<TrainStationPriceDO>> loadPrices(Set<? extends String> missingKeys, Map<String, TicketListDTO> priceKeyMap) {
        List<String> keys = new ArrayList<>(missingKeys);
        StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
        List<Object> values = stringRedisTemplate.executePipelined((RedisCallback<String>) connection -> {
            keys.forEach(each -> connection.stringCommands().get((cacheRedisPrefix + each).getBytes()));
            return null;
        });
        Map<String, List<TrainStationPriceDO>> result = new HashMap<>(keys.size() * 2);
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            Object value = i < values.size() ? values.get(i) : null;
            String priceStr = value != null ? value.toString() : loadPriceFromDb(key, priceKeyMap.get(key));
            result.put(key, Collections.unmodifiableList(JSON.parseArray(priceStr, TrainStationPriceDO.class)));
        }
        return result;
    }

    private String loadPriceFromDb(String key, TicketListDTO train) {
        return distributedCache.safeGet(
                key,
                String.class,
                () -> {
                    LambdaQueryWrapper<TrainStationPriceDO> queryWrapper = Wrappers.lambdaQuery(TrainStationPriceDO.class)
                            .eq(TrainStationPriceDO::getDeparture, train.getDeparture())
                            .eq(TrainStationPriceDO::getArrival, train.getArrival())
                            .eq(TrainStationPriceDO::getTrainId, train.getTrainId());
                    return JSON.toJSONString(trainStationPriceMapper.selectList(queryWrapper));
                },
                ADVANCE_TICKET_DAY,
                TimeUnit.DAYS);
    }

    private int loadSeatMargin(TicketListDTO train, TrainStationPriceDO item) {
        String seatType = String.valueOf(item.getSeatType());
        Map<String, String> seatMarginMap = seatMarginCacheLoader.load(train.getTrainId(), seatType, item.getDeparture(), item.getArrival());
        return Optional.ofNullable(seatMarginMap.get(seatType)).map(Integer::parseInt).orElse(0);
    }

    private static TicketListDTO copyOf(TicketListDTO source) {
        TicketListDTO target = new TicketListDTO();
        target.setTrainId(source.getTrainId());
        target.setTrainNumber(source.getTrainNumber());
        target.setDepartureTime(source.getDepartureTime());
        target.setArrivalTime(source.getArrivalTime());
        target.setDuration(source.getDuration());
        target.setDaysArrived(source.getDaysArrived());
        target.setDeparture(source.getDeparture());
        target.setArrival(source.getArrival());
        target.setDepartureFlag(source.getDepartureFlag());
        target.setArrivalFlag(source.getArrivalFlag());
        target.setTrainType(source.getTrainType());
        target.setSaleTime(source.getSaleTime());
        target.setSaleStatus(source.getSaleStatus());
        target.setTrainTags(source.getTrainTags());
        target.setTrainBrand(source.getTrainBrand());
        return target;
    }
}
