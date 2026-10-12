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

package org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.select;

import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.TrainStationPriceMapper;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.PurchaseTicketPassengerDetailDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.PurchaseTicketReqDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.UserRemoteService;
import org.opengoofy.index12306.biz.ticketservice.service.SeatService;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.opengoofy.index12306.framework.starter.designpattern.strategy.AbstractExecuteStrategy;
import org.opengoofy.index12306.framework.starter.designpattern.strategy.AbstractStrategyChoose;

import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * 选座异常的传递：业务异常（余票不足）要原样抛出，只有找不到选座策略才提示「未适配」
 */
class TrainSeatTypeSelectorTest {

    private static final String SOLD_OUT = "站点余票不足，请尝试更换座位类型或选择其它站点";

    private final AbstractStrategyChoose strategyChoose = mock(AbstractStrategyChoose.class);

    private final TrainSeatTypeSelector selector = new TrainSeatTypeSelector(
            mock(SeatService.class),
            mock(UserRemoteService.class),
            mock(TrainStationPriceMapper.class),
            strategyChoose,
            mock(ThreadPoolExecutor.class));

    @Test
    void soldOutDuringSeatSelectionIsNotReportedAsUnsupportedTrainType() {
        doReturn(mock(AbstractExecuteStrategy.class)).when(strategyChoose).choose(anyString(), isNull());
        doThrow(new ServiceException(SOLD_OUT)).when(strategyChoose).chooseAndExecuteResp(anyString(), any());

        assertThatThrownBy(() -> selector.select(0, request()))
                .isInstanceOf(ServiceException.class)
                .hasMessage(SOLD_OUT);
    }

    @Test
    void missingStrategyIsStillReportedAsUnsupportedTrainType() {
        doThrow(new ServiceException("[高铁一等座] 策略未定义")).when(strategyChoose).choose(anyString(), isNull());

        assertThatThrownBy(() -> selector.select(0, request()))
                .isInstanceOf(ServiceException.class)
                .hasMessage("当前车次列车类型暂未适配，请购买G35或G39车次");
    }

    private static PurchaseTicketReqDTO request() {
        PurchaseTicketPassengerDetailDTO passenger = new PurchaseTicketPassengerDetailDTO();
        passenger.setPassengerId("1");
        passenger.setSeatType(1);
        PurchaseTicketReqDTO requestParam = new PurchaseTicketReqDTO();
        requestParam.setTrainId("2");
        requestParam.setDeparture("北京南");
        requestParam.setArrival("杭州东");
        requestParam.setPassengers(List.of(passenger));
        return requestParam;
    }
}
