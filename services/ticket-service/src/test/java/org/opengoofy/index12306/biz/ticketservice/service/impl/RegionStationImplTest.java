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

package org.opengoofy.index12306.biz.ticketservice.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.RegionMapper;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.StationMapper;
import org.opengoofy.index12306.biz.ticketservice.dto.req.RegionStationQueryReqDTO;
import org.opengoofy.index12306.framework.starter.cache.DistributedCache;
import org.opengoofy.index12306.framework.starter.convention.exception.ClientException;
import org.redisson.api.RedissonClient;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link RegionStationImpl} 单元测试。
 *
 * 只覆盖本次修复的场景：请求同时没有传 name 和 queryType 时，之前会在
 * {@code switch (requestParam.getQueryType())} 上抛出未经处理的
 * NullPointerException（已通过 `/api/ticket-service/region-station/query`
 * 不带任何参数直连验证过），现在应该在进入 switch 之前就被识别成
 * ClientException，不应该再接触 mapper/缓存/分布式锁这些依赖。
 */
@ExtendWith(MockitoExtension.class)
class RegionStationImplTest {

    @Mock
    private RegionMapper regionMapper;

    @Mock
    private StationMapper stationMapper;

    @Mock
    private DistributedCache distributedCache;

    @Mock
    private RedissonClient redissonClient;

    @Test
    void listRegionStation_blankNameAndNullQueryType_throwsClientExceptionNotNPE() {
        RegionStationImpl regionStationImpl = new RegionStationImpl(regionMapper, stationMapper, distributedCache, redissonClient);
        RegionStationQueryReqDTO requestParam = new RegionStationQueryReqDTO();
        // name 和 queryType 都不设置，模拟客户端漏传参数的真实请求

        assertThatThrownBy(() -> regionStationImpl.listRegionStation(requestParam))
                .isInstanceOf(ClientException.class)
                .hasMessage("查询失败，请检查查询参数是否正确");

        // 校验：在抛出 ClientException 之前就应该结束，不应该走到缓存/数据库查询那一步
        verifyNoInteractions(regionMapper, stationMapper, distributedCache, redissonClient);
    }
}
