#!/usr/bin/env bash
# 实验 3：订单基因法分片路由一致性单测（不依赖中间件）
source "$(dirname "$0")/common.sh"
cd "$ROOT_DIR"
./mvnw -q -pl services/order-service -am test \
  -Dskip.unit.tests=false \
  -Dtest=OrderCommonTableComplexAlgorithmTest \
  -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tee "$RESULT_DIR/shard-test.log" | grep -E '基因法路由|Tests run|FAIL|ERROR' || true
