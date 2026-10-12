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

package org.opengoofy.index12306.biz.ticketservice.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.TrainStationPriceDO;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.TrainStationPriceMapper;
import org.opengoofy.index12306.biz.ticketservice.dto.req.TrainStationPriceUpdateReqDTO;
import org.opengoofy.index12306.biz.ticketservice.service.cache.TicketQueryCacheBroadcaster;
import org.opengoofy.index12306.biz.ticketservice.service.cache.TicketQueryLocalCache;
import org.opengoofy.index12306.framework.starter.cache.DistributedCache;
import org.opengoofy.index12306.framework.starter.convention.exception.ClientException;
import org.springframework.stereotype.Service;

import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_STATION_PRICE;

/**
 * 区间票价调整：先更新 DB，再删 Redis，最后本地失效 + Pub/Sub 广播其他节点
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrainStationPriceAdminService {

    private final TrainStationPriceMapper trainStationPriceMapper;
    private final DistributedCache distributedCache;
    private final TicketQueryCacheBroadcaster ticketQueryCacheBroadcaster;

    public void updatePrice(TrainStationPriceUpdateReqDTO requestParam) {
        if (requestParam.getPrice() == null || requestParam.getPrice() <= 0) {
            throw new ClientException("价格必须大于 0");
        }
        TrainStationPriceDO updateDO = new TrainStationPriceDO();
        updateDO.setPrice(requestParam.getPrice());
        LambdaUpdateWrapper<TrainStationPriceDO> updateWrapper = Wrappers.lambdaUpdate(TrainStationPriceDO.class)
                .eq(TrainStationPriceDO::getTrainId, requestParam.getTrainId())
                .eq(TrainStationPriceDO::getDeparture, requestParam.getDeparture())
                .eq(TrainStationPriceDO::getArrival, requestParam.getArrival())
                .eq(TrainStationPriceDO::getSeatType, requestParam.getSeatType());
        int updated = trainStationPriceMapper.update(updateDO, updateWrapper);
        if (updated <= 0) {
            throw new ClientException("区间票价不存在");
        }
        String priceKey = String.format(TRAIN_STATION_PRICE, requestParam.getTrainId(), requestParam.getDeparture(), requestParam.getArrival());
        distributedCache.delete(priceKey);
        ticketQueryCacheBroadcaster.evictAndBroadcast(TicketQueryLocalCache.PRICE, priceKey);
        log.info("[票价调整] {} 座位类型 {} 新价格 {}，已删除 Redis 并广播本地缓存失效", priceKey, requestParam.getSeatType(), requestParam.getPrice());
    }
}
