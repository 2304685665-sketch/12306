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

package org.opengoofy.index12306.biz.ticketservice.controller;

import lombok.RequiredArgsConstructor;
import org.opengoofy.index12306.biz.ticketservice.mq.consumer.DelayCloseOrderConsumer;
import org.opengoofy.index12306.biz.ticketservice.mq.domain.MessageWrapper;
import org.opengoofy.index12306.biz.ticketservice.mq.event.DelayCloseOrderEvent;
import org.opengoofy.index12306.biz.ticketservice.remote.TicketOrderRemoteService;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderDetailRespDTO;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.dto.TrainPurchaseTicketRespDTO;
import org.opengoofy.index12306.framework.starter.common.toolkit.BeanUtil;
import org.opengoofy.index12306.framework.starter.convention.exception.ClientException;
import org.opengoofy.index12306.framework.starter.convention.result.Result;
import org.opengoofy.index12306.framework.starter.web.Results;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 压测专用：模拟同一订单的延迟关单消息被重复投递，验证座位 / 余票 / 令牌只回滚一次
 *
 * <pre>
 * redelivery：MQ 重投，消息体完全相同（同一个 MessageWrapper），由 @Idempotent 消费幂等拦截
 * duplicate ：业务重复发送，每次都是新的 MessageWrapper（uuid / timestamp 不同），只能靠订单状态兜底
 * </pre>
 */
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ticket.bench.enabled", havingValue = "true")
public class DelayCloseBenchController {

    private final DelayCloseOrderConsumer delayCloseOrderConsumer;
    private final TicketOrderRemoteService ticketOrderRemoteService;

    /**
     * redelivery 模式下同一订单复用同一个消息对象，保证与 MQ 重投时的消息体一致
     */
    private final Map<String, MessageWrapper<DelayCloseOrderEvent>> redeliveryMessages = new ConcurrentHashMap<>();

    @PostMapping("/api/ticket-service/bench/delay-close/replay")
    public Result<Map<String, String>> replay(@RequestParam("orderSn") String orderSn, @RequestParam("mode") String mode) {
        MessageWrapper<DelayCloseOrderEvent> message = switch (mode) {
            case "redelivery" -> redeliveryMessages.computeIfAbsent(orderSn, this::buildMessage);
            case "duplicate" -> buildMessage(orderSn);
            default -> throw new ClientException("unknown mode: " + mode);
        };
        Map<String, String> result = new LinkedHashMap<>();
        try {
            delayCloseOrderConsumer.onMessage(message);
            result.put("outcome", "consumed");
        } catch (Throwable ex) {
            result.put("outcome", "rejected");
            result.put("reason", ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
        return Results.success(result);
    }

    private MessageWrapper<DelayCloseOrderEvent> buildMessage(String orderSn) {
        TicketOrderDetailRespDTO order = ticketOrderRemoteService.queryTicketOrderByOrderSn(orderSn).getData();
        if (order == null) {
            throw new ClientException("订单不存在：" + orderSn);
        }
        DelayCloseOrderEvent event = DelayCloseOrderEvent.builder()
                .orderSn(orderSn)
                .trainId(String.valueOf(order.getTrainId()))
                .departure(order.getDeparture())
                .arrival(order.getArrival())
                .trainPurchaseTicketResults(BeanUtil.convert(order.getPassengerDetails(), TrainPurchaseTicketRespDTO.class))
                .build();
        return new MessageWrapper<>(orderSn, event);
    }
}
