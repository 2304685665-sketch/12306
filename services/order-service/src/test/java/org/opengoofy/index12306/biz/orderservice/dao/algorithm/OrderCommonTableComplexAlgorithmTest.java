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


package org.opengoofy.index12306.biz.orderservice.dao.algorithm;

import org.apache.shardingsphere.sharding.api.sharding.complex.ComplexKeysShardingValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.biz.orderservice.service.orderid.OrderIdGeneratorManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 订单基因法分片路由一致性测试：同一笔订单按 user_id 路由和按 order_sn 路由必须落到同一张表
 */
class OrderCommonTableComplexAlgorithmTest {

    private static final int SAMPLES = 100_000;

    private static final int SHARDING_COUNT = 16;

    private OrderCommonTableComplexAlgorithm algorithm;

    private List<String> tables;

    @BeforeEach
    void setUp() {
        algorithm = new OrderCommonTableComplexAlgorithm();
        Properties props = new Properties();
        props.setProperty("sharding-count", String.valueOf(SHARDING_COUNT));
        algorithm.init(props);
        tables = new ArrayList<>();
        for (int i = 0; i < SHARDING_COUNT; i++) {
            tables.add("t_order_" + i);
        }
    }

    @Test
    void orderSnAndUserIdRouteToSameTable_afterGenePadding() {
        Random random = new Random(20261012L);
        int mismatch = 0;
        for (int i = 0; i < SAMPLES; i++) {
            long userId = randomSnowflakeLike(random);
            long distributedId = randomSnowflakeLike(random);
            String orderSn = OrderIdGeneratorManager.buildOrderSn(distributedId, userId);
            if (!route("user_id", String.valueOf(userId)).equals(route("order_sn", orderSn))) {
                mismatch++;
            }
        }
        assertThat(mismatch).isZero();
    }

    @Test
    void legacyUnpaddedGene_misroutesUsersWhoseGeneHasLeadingZero() {
        Random random = new Random(20261012L);
        int affectedUsers = 0;
        int mismatch = 0;
        for (int i = 0; i < SAMPLES; i++) {
            long userId = randomSnowflakeLike(random);
            long distributedId = randomSnowflakeLike(random);
            // 修复前的写法：基因位不补零
            String legacyOrderSn = distributedId + String.valueOf(userId % 1000000);
            if (userId % 1000000 < 100000) {
                affectedUsers++;
            }
            if (!route("user_id", String.valueOf(userId)).equals(route("order_sn", legacyOrderSn))) {
                mismatch++;
            }
        }
        System.out.printf("[基因法路由] 样本 %d，基因位不足 6 位的用户 %.2f%%，按订单号路由错表 %.2f%%%n",
                SAMPLES, affectedUsers * 100.0 / SAMPLES, mismatch * 100.0 / SAMPLES);
        assertThat(mismatch).isGreaterThan(0);
    }

    @SuppressWarnings("unchecked")
    private String route(String column, String value) {
        Map<String, Collection<Comparable<?>>> shardingValues = new HashMap<>();
        shardingValues.put(column, Collections.<Comparable<?>>singletonList(value));
        ComplexKeysShardingValue<Comparable<?>> shardingValue = new ComplexKeysShardingValue<>("t_order", shardingValues, Collections.emptyMap());
        Collection<String> result = algorithm.doSharding(tables, shardingValue);
        return result.iterator().next();
    }

    /**
     * 19 位、与雪花 ID 同量级的正整数
     */
    private static long randomSnowflakeLike(Random random) {
        return 1_000_000_000_000_000_000L + Math.floorMod(random.nextLong(), 8_000_000_000_000_000_000L);
    }
}
