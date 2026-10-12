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

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.biz.ticketservice.dto.req.TicketPageQueryReqDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.resp.TicketPageQueryRespDTO;
import org.opengoofy.index12306.biz.ticketservice.service.TicketService;
import org.opengoofy.index12306.biz.ticketservice.service.ratelimit.RedisFixedWindowRateLimiter;
import org.opengoofy.index12306.framework.starter.convention.exception.ClientException;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 查票接口限流开关：默认关闭时完全不触达限流器（原有查票行为不变）；开启后超限返回「查询过于频繁」且不进入查询
 */
class TicketControllerRateLimitTest {

    private TicketService ticketService;
    private RedisFixedWindowRateLimiter rateLimiter;
    private TicketController controller;
    private HttpServletRequest request;
    private final TicketPageQueryReqDTO req = new TicketPageQueryReqDTO();
    private final TicketPageQueryRespDTO resp = new TicketPageQueryRespDTO();

    @BeforeEach
    void setUp() {
        ticketService = mock(TicketService.class);
        rateLimiter = mock(RedisFixedWindowRateLimiter.class);
        controller = new TicketController(ticketService, rateLimiter);
        request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("10.0.0.1");
        when(ticketService.pageListTicketQueryV3(any())).thenReturn(resp);
    }

    @Test
    void limiterOffByDefaultNeverTouchesRedis() {
        assertThat(controller.pageListTicketQuery(req, request).getData()).isSameAs(resp);
        verifyNoInteractions(rateLimiter);
    }

    @Test
    void limiterOnAndAcquiredQueriesNormally() throws Exception {
        setPermits(5);
        when(rateLimiter.tryAcquire(anyString(), anyLong(), anyLong())).thenReturn(true);
        assertThat(controller.pageListTicketQuery(req, request).getData()).isSameAs(resp);
        verify(rateLimiter).tryAcquire(eq("ticket-query:10.0.0.1"), eq(5L), eq(1000L));
    }

    @Test
    void limiterOnAndRejectedSkipsQuery() throws Exception {
        setPermits(5);
        when(rateLimiter.tryAcquire(anyString(), anyLong(), anyLong())).thenReturn(false);
        assertThatThrownBy(() -> controller.pageListTicketQuery(req, request))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("查询过于频繁");
        verify(ticketService, never()).pageListTicketQueryV3(any());
    }

    private void setPermits(long permits) throws Exception {
        Field field = TicketController.class.getDeclaredField("queryPermitsPerSecond");
        field.setAccessible(true);
        field.setLong(controller, permits);
    }
}
