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
import org.opengoofy.index12306.biz.ticketservice.dto.req.TicketPageQueryReqDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.resp.TicketPageQueryRespDTO;
import org.opengoofy.index12306.biz.ticketservice.service.TicketService;
import org.opengoofy.index12306.biz.ticketservice.service.cache.TicketQueryCacheBroadcaster;
import org.opengoofy.index12306.biz.ticketservice.service.cache.TicketQueryLocalCache;
import org.opengoofy.index12306.framework.starter.convention.exception.ClientException;
import org.opengoofy.index12306.framework.starter.convention.result.Result;
import org.opengoofy.index12306.framework.starter.web.Results;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 压测专用接口：同一进程内切换查询版本做对照实验，只有 ticket.bench.enabled=true 时才注册
 */
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ticket.bench.enabled", havingValue = "true")
public class TicketQueryBenchController {

    private final TicketService ticketService;
    private final TicketQueryLocalCache ticketQueryLocalCache;
    private final TicketQueryCacheBroadcaster ticketQueryCacheBroadcaster;

    /**
     * version: v1（原始逐条读 Redis） / v2（Pipeline） / v3（L1 + Pipeline） / v3-static（v3 去掉余票读取，用于定位开销）
     */
    @GetMapping("/api/ticket-service/bench/ticket/query")
    public Result<TicketPageQueryRespDTO> query(@RequestParam("version") String version, TicketPageQueryReqDTO requestParam) {
        TicketPageQueryRespDTO result = switch (version) {
            case "v1" -> ticketService.pageListTicketQueryV1(requestParam);
            case "v2" -> ticketService.pageListTicketQueryV2(requestParam);
            case "v3" -> ticketService.pageListTicketQueryV3(requestParam, true);
            case "v3-static" -> ticketService.pageListTicketQueryV3(requestParam, false);
            default -> throw new ClientException("unknown version: " + version);
        };
        return Results.success(result);
    }

    private final AtomicReference<long[]> statsBaseline = new AtomicReference<>(new long[]{0L, 0L});

    /**
     * 返回自上次 reset 以来的 L1 命中率
     */
    @GetMapping("/api/ticket-service/bench/local-cache/stats")
    public Result<Map<String, Object>> stats() {
        long[] now = ticketQueryLocalCache.totalHitAndMiss();
        long[] base = statsBaseline.get();
        long hit = now[0] - base[0];
        long miss = now[1] - base[1];
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodeId", ticketQueryCacheBroadcaster.getNodeId());
        result.put("hit", hit);
        result.put("miss", miss);
        result.put("hitRate", hit + miss == 0 ? 0D : (double) hit / (hit + miss));
        result.put("caches", ticketQueryLocalCache.stats());
        return Results.success(result);
    }

    @PostMapping("/api/ticket-service/bench/local-cache/stats/reset")
    public Result<Void> resetStats() {
        statsBaseline.set(ticketQueryLocalCache.totalHitAndMiss());
        return Results.success();
    }

    @PostMapping("/api/ticket-service/bench/local-cache/clear")
    public Result<Void> clear() {
        ticketQueryLocalCache.invalidateAll();
        return Results.success();
    }
}
