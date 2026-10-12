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

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.TrainStationPriceDO;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.TrainStationPriceMapper;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.TicketListDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.TicketPageQueryReqDTO;
import org.opengoofy.index12306.biz.ticketservice.service.cache.SeatMarginCacheLoader;
import org.opengoofy.index12306.biz.ticketservice.service.cache.TicketQueryLocalCache;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.filter.query.TrainTicketQueryParamBaseVerifyChainFilter;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.filter.query.TrainTicketQueryParamNotNullChainFilter;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.filter.query.TrainTicketQueryParamVerifyChainFilter;
import org.opengoofy.index12306.framework.starter.cache.DistributedCache;
import org.opengoofy.index12306.framework.starter.convention.exception.ClientException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.QUERY_ALL_REGION_LIST;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.REGION_TRAIN_STATION_MAPPING;

/**
 * 车票查询 v3（L1 + Redis）单元测试：只验证缓存行为，不依赖真实 Redis
 */
class MultiLevelTicketQueryExecutorTest {

    private static final String ROUTE_KEY = "index12306-ticket-service:region_train_station:北京_杭州";
    private static final String EMPTY_ROUTE_KEY = "index12306-ticket-service:region_train_station:杭州_北京";

    private StringRedisTemplate stringRedisTemplate;
    private HashOperations<String, Object, Object> hashOperations;
    private SeatMarginCacheLoader seatMarginCacheLoader;
    private TrainTicketQueryParamVerifyChainFilter regionVerifyFilter;
    private MultiLevelTicketQueryExecutor executor;
    private AtomicInteger warmUpCount;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        DistributedCache distributedCache = mock(DistributedCache.class);
        stringRedisTemplate = mock(StringRedisTemplate.class);
        hashOperations = mock(HashOperations.class);
        when(distributedCache.getInstance()).thenReturn(stringRedisTemplate);
        when(stringRedisTemplate.opsForHash()).thenReturn((HashOperations) hashOperations);

        Map<Object, Object> stations = new HashMap<>();
        stations.put("BJP", "北京");
        stations.put("HZH", "杭州");
        when(hashOperations.entries(QUERY_ALL_REGION_LIST)).thenReturn(stations);
        when(hashOperations.entries(REGION_TRAIN_STATION_MAPPING)).thenReturn(stations);
        Map<Object, Object> trains = new HashMap<>();
        trains.put("2_北京南_杭州东", JSON.toJSONString(train("2", "G39", "19:04")));
        when(hashOperations.entries(ROUTE_KEY)).thenReturn(trains);
        when(hashOperations.entries(EMPTY_ROUTE_KEY)).thenReturn(Collections.emptyMap());

        seatMarginCacheLoader = mock(SeatMarginCacheLoader.class);
        regionVerifyFilter = mock(TrainTicketQueryParamVerifyChainFilter.class);
        executor = new MultiLevelTicketQueryExecutor(
                distributedCache,
                new TicketQueryLocalCache(300, 1000),
                seatMarginCacheLoader,
                mock(TrainStationPriceMapper.class),
                new TrainTicketQueryParamNotNullChainFilter(),
                new TrainTicketQueryParamBaseVerifyChainFilter(),
                regionVerifyFilter);
        ReflectionTestUtils.setField(executor, "cacheRedisPrefix", "");
        warmUpCount = new AtomicInteger();
    }

    @Test
    void secondQueryServesStaticDataFromLocalCache() {
        List<Object> prices = List.of(priceJson());
        List<Object> remaining = Arrays.asList("10", "20");
        when(stringRedisTemplate.executePipelined(any(RedisCallback.class))).thenReturn(prices, remaining, remaining);

        List<TicketListDTO> first = executor.query(request("BJP", "HZH"), true, warmUpCount::incrementAndGet);
        List<TicketListDTO> second = executor.query(request("BJP", "HZH"), true, warmUpCount::incrementAndGet);

        assertThat(first).hasSize(1);
        assertThat(first.get(0).getSeatClassList()).extracting("quantity").containsExactly(10, 20);
        assertThat(first.get(0).getSeatClassList()).extracting("price").containsExactly(new BigDecimal("2313.0"), new BigDecimal("1064.0"));
        assertThat(second.get(0).getSeatClassList()).extracting("quantity").containsExactly(10, 20);
        // 站点字典、站点映射、车次列表各只读一次 Redis；第二次查询只剩 1 次余票 Pipeline
        verify(hashOperations, times(1)).entries(QUERY_ALL_REGION_LIST);
        verify(hashOperations, times(1)).entries(REGION_TRAIN_STATION_MAPPING);
        verify(hashOperations, times(1)).entries(ROUTE_KEY);
        verify(stringRedisTemplate, times(3)).executePipelined(any(RedisCallback.class));
        assertThat(warmUpCount).hasValue(0);
    }

    @Test
    void returnedObjectsAreCopiesNotSharedTemplates() {
        when(stringRedisTemplate.executePipelined(any(RedisCallback.class)))
                .thenReturn(List.of(priceJson()), Arrays.asList("10", "20"), Arrays.asList("9", "19"));

        List<TicketListDTO> first = executor.query(request("BJP", "HZH"), true, warmUpCount::incrementAndGet);
        first.get(0).setTrainNumber("TAMPERED");
        first.get(0).getSeatClassList().clear();
        List<TicketListDTO> second = executor.query(request("BJP", "HZH"), true, warmUpCount::incrementAndGet);

        assertThat(second.get(0).getTrainNumber()).isEqualTo("G39");
        assertThat(second.get(0).getSeatClassList()).extracting("quantity").containsExactly(9, 19);
        assertThat(second.get(0)).isNotSameAs(first.get(0));
    }

    @Test
    void routeWithoutTrainsIsCachedAsEmptyAndWarmsUpOnlyOnce() {
        for (int i = 0; i < 5; i++) {
            assertThat(executor.query(request("HZH", "BJP"), true, warmUpCount::incrementAndGet)).isEmpty();
        }
        // 只在第一次回源构建（加锁查 DB 的 v1 逻辑），后续 4 次全部被本地空值缓存挡住
        assertThat(warmUpCount).hasValue(1);
        verify(hashOperations, times(2)).entries(EMPTY_ROUTE_KEY);
        verify(stringRedisTemplate, never()).executePipelined(any(RedisCallback.class));
    }

    @Test
    void unknownStationIsRejectedLocallyWithoutTouchingRouteData() {
        assertThatThrownBy(() -> executor.query(request("BJP", "XXX"), true, warmUpCount::incrementAndGet))
                .isInstanceOf(ClientException.class)
                .hasMessage("出发地或目的地不存在");
        assertThatThrownBy(() -> executor.query(request("XXX", "HZH"), true, warmUpCount::incrementAndGet))
                .isInstanceOf(ClientException.class);
        verify(hashOperations, times(1)).entries(QUERY_ALL_REGION_LIST);
        verify(hashOperations, never()).entries(REGION_TRAIN_STATION_MAPPING);
        verify(stringRedisTemplate, never()).executePipelined(any(RedisCallback.class));
    }

    @Test
    void missingRemainingTicketFallsBackToSeatMarginLoader() {
        when(stringRedisTemplate.executePipelined(any(RedisCallback.class)))
                .thenReturn(List.of(priceJson()), Arrays.asList(null, "20"));
        Map<String, String> margin = new HashMap<>();
        margin.put("0", "7");
        when(seatMarginCacheLoader.load(eq("2"), eq("0"), anyString(), anyString())).thenReturn(margin);

        List<TicketListDTO> result = executor.query(request("BJP", "HZH"), true, warmUpCount::incrementAndGet);

        assertThat(result.get(0).getSeatClassList()).extracting("quantity").containsExactly(7, 20);
    }

    @Test
    void withoutRemainingSkipsRemainingPipeline() {
        when(stringRedisTemplate.executePipelined(any(RedisCallback.class))).thenReturn(List.of(priceJson()));

        executor.query(request("BJP", "HZH"), false, warmUpCount::incrementAndGet);
        executor.query(request("BJP", "HZH"), false, warmUpCount::incrementAndGet);

        // 只有第一次加载票价的那 1 次 Pipeline
        verify(stringRedisTemplate, times(1)).executePipelined(any(RedisCallback.class));
    }

    @Test
    void coldRedisTriggersWarmUpThenReadsAgain() {
        Map<Object, Object> stations = new HashMap<>();
        stations.put("BJP", "北京");
        stations.put("HZH", "杭州");
        when(hashOperations.entries(REGION_TRAIN_STATION_MAPPING)).thenReturn(Collections.emptyMap(), stations);
        when(stringRedisTemplate.executePipelined(any(RedisCallback.class))).thenReturn(List.of(priceJson()), Arrays.asList("1", "2"));

        List<TicketListDTO> result = executor.query(request("BJP", "HZH"), true, warmUpCount::incrementAndGet);

        assertThat(result).hasSize(1);
        assertThat(warmUpCount).hasValue(1);
    }

    private static TicketPageQueryReqDTO request(String from, String to) {
        TicketPageQueryReqDTO requestParam = new TicketPageQueryReqDTO();
        requestParam.setFromStation(from);
        requestParam.setToStation(to);
        requestParam.setDepartureDate(new Date());
        return requestParam;
    }

    private static TicketListDTO train(String trainId, String trainNumber, String departureTime) {
        TicketListDTO dto = new TicketListDTO();
        dto.setTrainId(trainId);
        dto.setTrainNumber(trainNumber);
        dto.setDepartureTime(departureTime);
        dto.setArrivalTime("23:00");
        dto.setDeparture("北京南");
        dto.setArrival("杭州东");
        dto.setTrainType(0);
        dto.setTrainBrand("0");
        return dto;
    }

    private static String priceJson() {
        List<TrainStationPriceDO> prices = new ArrayList<>();
        prices.add(price(0, 231300));
        prices.add(price(1, 106400));
        return JSON.toJSONString(prices);
    }

    private static TrainStationPriceDO price(int seatType, int cents) {
        TrainStationPriceDO priceDO = new TrainStationPriceDO();
        priceDO.setTrainId(2L);
        priceDO.setDeparture("北京南");
        priceDO.setArrival("杭州东");
        priceDO.setSeatType(seatType);
        priceDO.setPrice(cents);
        return priceDO;
    }
}
