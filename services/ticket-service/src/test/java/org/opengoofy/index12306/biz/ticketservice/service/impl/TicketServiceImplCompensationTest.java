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
import org.opengoofy.index12306.biz.ticketservice.common.enums.TicketChainMarkEnum;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.PurchaseTicketPassengerDetailDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.PurchaseTicketReqDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.resp.TicketPurchaseRespDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderDetailRespDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderPassengerDetailRespDTO;
import org.opengoofy.index12306.biz.ticketservice.service.TicketService;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.dto.TokenResultDTO;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.tokenbucket.TicketAvailabilityTokenBucket;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.opengoofy.index12306.framework.starter.designpattern.chain.AbstractChainContext;
import org.opengoofy.index12306.framework.starter.designpattern.chain.AbstractChainHandler;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.env.ConfigurableEnvironment;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 购票 v2 失败补偿：令牌已扣但下单失败时归还令牌；补偿自身失败只记日志，不覆盖原始业务异常；成功或关闭补偿时不归还
 */
class TicketServiceImplCompensationTest {

    private TicketAvailabilityTokenBucket tokenBucket;
    private TicketService self;
    private TicketServiceImpl ticketService;
    private PurchaseTicketReqDTO request;

    @BeforeEach
    void setUp() throws Exception {
        tokenBucket = mock(TicketAvailabilityTokenBucket.class);
        when(tokenBucket.takeTokenFromBucket(any())).thenReturn(TokenResultDTO.builder().tokenIsNull(false).build());
        RedissonClient redissonClient = mock(RedissonClient.class);
        when(redissonClient.getFairLock(anyString())).thenReturn(mock(RLock.class));
        ConfigurableEnvironment environment = mock(ConfigurableEnvironment.class);
        when(environment.resolvePlaceholders(anyString())).thenAnswer(inv -> inv.getArgument(0));
        ticketService = newTicketService(redissonClient, environment);
        self = mock(TicketService.class);
        setField("ticketService", self);
        setField("compensateOnFailure", true);

        PurchaseTicketPassengerDetailDTO p1 = new PurchaseTicketPassengerDetailDTO();
        p1.setPassengerId("1");
        p1.setSeatType(0);
        PurchaseTicketPassengerDetailDTO p2 = new PurchaseTicketPassengerDetailDTO();
        p2.setPassengerId("2");
        p2.setSeatType(1);
        request = new PurchaseTicketReqDTO();
        request.setTrainId("1");
        request.setDeparture("北京南");
        request.setArrival("济南西");
        request.setPassengers(List.of(p1, p2));
    }

    @Test
    void orderFailureReturnsTakenTokens() {
        when(self.executePurchaseTickets(any())).thenThrow(new ServiceException("订单服务调用失败"));

        assertThatThrownBy(() -> ticketService.purchaseTicketsV2(request)).hasMessage("订单服务调用失败");

        ArgumentCaptor<TicketOrderDetailRespDTO> captor = ArgumentCaptor.forClass(TicketOrderDetailRespDTO.class);
        verify(tokenBucket, times(1)).rollbackInBucket(captor.capture());
        TicketOrderDetailRespDTO returned = captor.getValue();
        assertThat(returned.getTrainId()).isEqualTo(1L);
        assertThat(returned.getDeparture()).isEqualTo("北京南");
        assertThat(returned.getArrival()).isEqualTo("济南西");
        assertThat(returned.getPassengerDetails()).extracting(TicketOrderPassengerDetailRespDTO::getSeatType).containsExactly(0, 1);
    }

    @Test
    void compensationFailureDoesNotMaskOriginalError() {
        when(self.executePurchaseTickets(any())).thenThrow(new ServiceException("订单服务调用失败"));
        doThrow(new IllegalStateException("Redis 不可用")).when(tokenBucket).rollbackInBucket(any());

        assertThatThrownBy(() -> ticketService.purchaseTicketsV2(request))
                .isInstanceOf(ServiceException.class)
                .hasMessage("订单服务调用失败");
        verify(tokenBucket, times(1)).rollbackInBucket(any());
    }

    @Test
    void successDoesNotReturnTokens() {
        TicketPurchaseRespDTO ok = new TicketPurchaseRespDTO();
        when(self.executePurchaseTickets(any())).thenReturn(ok);

        assertThat(ticketService.purchaseTicketsV2(request)).isSameAs(ok);
        verify(tokenBucket, never()).rollbackInBucket(any());
    }

    @Test
    void compensationDisabledKeepsUpstreamBehaviour() throws Exception {
        setField("compensateOnFailure", false);
        when(self.executePurchaseTickets(any())).thenThrow(new ServiceException("订单服务调用失败"));

        assertThatThrownBy(() -> ticketService.purchaseTicketsV2(request)).hasMessage("订单服务调用失败");
        verify(tokenBucket, never()).rollbackInBucket(any());
    }

    private TicketServiceImpl newTicketService(RedissonClient redissonClient, ConfigurableEnvironment environment) throws Exception {
        Constructor<?> constructor = TicketServiceImpl.class.getConstructors()[0];
        Class<?>[] types = constructor.getParameterTypes();
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            if (types[i] == TicketAvailabilityTokenBucket.class) {
                args[i] = tokenBucket;
            } else if (types[i] == RedissonClient.class) {
                args[i] = redissonClient;
            } else if (types[i] == ConfigurableEnvironment.class) {
                args[i] = environment;
            } else if (types[i] == AbstractChainContext.class) {
                args[i] = passThroughChainContext();
            }
        }
        return (TicketServiceImpl) constructor.newInstance(args);
    }

    private void setField(String name, Object value) throws Exception {
        Field field = TicketServiceImpl.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(ticketService, value);
    }

    /**
     * AbstractChainContext 是 final 类：用真实对象，给购票责任链放一个空处理器（参数校验不在本测试范围）
     */
    @SuppressWarnings("unchecked")
    private static AbstractChainContext<Object> passThroughChainContext() throws Exception {
        AbstractChainContext<Object> context = new AbstractChainContext<>();
        Field container = AbstractChainContext.class.getDeclaredField("abstractChainHandlerContainer");
        container.setAccessible(true);
        Map<String, List<AbstractChainHandler>> handlers = (Map<String, List<AbstractChainHandler>>) container.get(context);
        handlers.put(TicketChainMarkEnum.TRAIN_PURCHASE_TICKET_FILTER.name(), List.of(mock(AbstractChainHandler.class)));
        return context;
    }
}
