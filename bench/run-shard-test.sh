#!/usr/bin/env bash
# 实验 3：订单基因法分片路由一致性单测（不依赖中间件）
source "$(dirname "$0")/common.sh"
ensure_java17
cd "$ROOT_DIR"
set +e
./mvnw -q -pl services/order-service -am test \
  -Dskip.unit.tests=false \
  -Dtest=OrderCommonTableComplexAlgorithmTest \
  -Dsurefire.failIfNoSpecifiedTests=false > "$RESULT_DIR/shard-test.log" 2>&1
rc=$?
grep -E '基因法路由|Tests run:|FAIL|ERROR\]' "$RESULT_DIR/shard-test.log" | head -20
exit $rc
