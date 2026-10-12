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
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.SeatMapper;
import org.opengoofy.index12306.biz.ticketservice.service.TrainStationService;
import org.opengoofy.index12306.framework.starter.cache.DistributedCache;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 并发抢票时，前面的请求在锁内选完座位后，后到的请求查到的可用车厢为空：
 * 这时不能再去拼 carriage_number in (...) 的 SQL，应直接返回空让上层走「余票不足」
 */
class SeatServiceImplTest {

    @Test
    void emptyCarriageListReturnsEmptyWithoutQueryingDatabase() {
        SeatMapper seatMapper = mock(SeatMapper.class);
        DistributedCache distributedCache = mock(DistributedCache.class);
        SeatServiceImpl seatService = new SeatServiceImpl(seatMapper, mock(TrainStationService.class), distributedCache);

        List<Integer> empty = seatService.listSeatRemainingTicket("2", "北京南", "杭州东", Collections.emptyList());
        List<Integer> nullList = seatService.listSeatRemainingTicket("2", "北京南", "杭州东", null);

        assertThat(empty).isEmpty();
        assertThat(nullList).isEmpty();
        verifyNoInteractions(seatMapper, distributedCache);
    }
}
