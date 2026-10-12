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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opengoofy.index12306.biz.ticketservice.common.enums.RefundTypeEnum;
import org.opengoofy.index12306.biz.ticketservice.common.enums.TicketChainMarkEnum;
import org.opengoofy.index12306.biz.ticketservice.dto.req.RefundTicketReqDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.PayRemoteService;
import org.opengoofy.index12306.biz.ticketservice.remote.TicketOrderRemoteService;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.RefundReqDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.RefundRespDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderDetailRespDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderPassengerDetailRespDTO;
import org.opengoofy.index12306.framework.starter.designpattern.chain.AbstractChainContext;
import org.opengoofy.index12306.framework.starter.designpattern.chain.AbstractChainHandler;
import org.opengoofy.index12306.framework.starter.web.Results;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 退票金额：部分退款只能退被选中乘客的票款，全部退款退整单票款
 */
class TicketServiceImplRefundTest {

    private static final String ORDER_SN = "1850000000000000000880001";

    private TicketOrderRemoteService ticketOrderRemoteService;
    private PayRemoteService payRemoteService;
    private TicketServiceImpl ticketService;

    private final TicketOrderPassengerDetailRespDTO zhang = passenger("张三", 106400);
    private final TicketOrderPassengerDetailRespDTO li = passenger("李四", 231300);
    private final TicketOrderPassengerDetailRespDTO wang = passenger("王五", 66300);

    @BeforeEach
    void setUp() throws Exception {
        ticketOrderRemoteService = mock(TicketOrderRemoteService.class);
        payRemoteService = mock(PayRemoteService.class);
        ticketService = newTicketService();

        TicketOrderDetailRespDTO order = new TicketOrderDetailRespDTO();
        order.setOrderSn(ORDER_SN);
        order.setPassengerDetails(List.of(zhang, li, wang));
        when(ticketOrderRemoteService.queryTicketOrderByOrderSn(anyString())).thenReturn(Results.success(order));
        when(payRemoteService.commonRefund(any())).thenReturn(Results.success(new RefundRespDTO()));
    }

    @Test
    void partialRefundOnlyRefundsSelectedPassengers() {
        // 只退「李四」一张
        when(ticketOrderRemoteService.queryTicketItemOrderById(any())).thenReturn(Results.success(List.of(li)));

        ticketService.commonTicketRefund(request(RefundTypeEnum.PARTIAL_REFUND));

        RefundReqDTO refund = capturedRefund();
        assertThat(refund.getRefundTypeEnum()).isEqualTo(RefundTypeEnum.PARTIAL_REFUND);
        assertThat(refund.getRefundDetailReqDTOList()).containsExactly(li);
        assertThat(refund.getRefundAmount()).as("部分退款金额应只包含被退乘客").isEqualTo(231300);
    }

    @Test
    void partialRefundOfTwoPassengersSumsOnlyThoseTwo() {
        when(ticketOrderRemoteService.queryTicketItemOrderById(any())).thenReturn(Results.success(List.of(zhang, wang)));

        ticketService.commonTicketRefund(request(RefundTypeEnum.PARTIAL_REFUND));

        assertThat(capturedRefund().getRefundAmount()).isEqualTo(106400 + 66300);
    }

    @Test
    void fullRefundRefundsWholeOrder() {
        ticketService.commonTicketRefund(request(RefundTypeEnum.FULL_REFUND));

        RefundReqDTO refund = capturedRefund();
        assertThat(refund.getRefundTypeEnum()).isEqualTo(RefundTypeEnum.FULL_REFUND);
        assertThat(refund.getRefundAmount()).isEqualTo(106400 + 231300 + 66300);
    }

    private RefundReqDTO capturedRefund() {
        ArgumentCaptor<RefundReqDTO> captor = ArgumentCaptor.forClass(RefundReqDTO.class);
        verify(payRemoteService).commonRefund(captor.capture());
        return captor.getValue();
    }

    private static RefundTicketReqDTO request(RefundTypeEnum type) {
        RefundTicketReqDTO requestParam = new RefundTicketReqDTO();
        requestParam.setOrderSn(ORDER_SN);
        requestParam.setType(type.getType());
        requestParam.setSubOrderRecordIdReqList(List.of("1"));
        return requestParam;
    }

    private static TicketOrderPassengerDetailRespDTO passenger(String name, int amount) {
        return TicketOrderPassengerDetailRespDTO.builder().realName(name).seatType(1).amount(amount).build();
    }

    /**
     * TicketServiceImpl 构造参数很多，这里只注入退票用到的依赖，其余为 null
     */
    private TicketServiceImpl newTicketService() throws Exception {
        Constructor<?> constructor = TicketServiceImpl.class.getConstructors()[0];
        Class<?>[] types = constructor.getParameterTypes();
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            if (types[i] == TicketOrderRemoteService.class) {
                args[i] = ticketOrderRemoteService;
            } else if (types[i] == PayRemoteService.class) {
                args[i] = payRemoteService;
            } else if (types[i] == AbstractChainContext.class) {
                args[i] = passThroughChainContext();
            }
        }
        return (TicketServiceImpl) constructor.newInstance(args);
    }

    /**
     * AbstractChainContext 是 final 类无法 mock：用真实对象，给退票责任链放一个空处理器（参数校验不在本测试范围）
     */
    @SuppressWarnings("unchecked")
    private static AbstractChainContext<Object> passThroughChainContext() throws Exception {
        AbstractChainContext<Object> context = new AbstractChainContext<>();
        Field container = AbstractChainContext.class.getDeclaredField("abstractChainHandlerContainer");
        container.setAccessible(true);
        Map<String, List<AbstractChainHandler>> handlers = (Map<String, List<AbstractChainHandler>>) container.get(context);
        handlers.put(TicketChainMarkEnum.TRAIN_REFUND_TICKET_FILTER.name(), List.of(mock(AbstractChainHandler.class)));
        return context;
    }
}
