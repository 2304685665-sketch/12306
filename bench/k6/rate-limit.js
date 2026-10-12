// 实验 6：自研 Redis Lua 固定窗口限流 vs 非原子实现（先 GET 再 INCR）
// 双节点交替、50 并发、共 500 次请求、同一个 key、上限 50、窗口 60s（实验期间不重置）
import http from 'k6/http';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

const NODES = (__ENV.NODES || 'http://127.0.0.1:9005,http://127.0.0.1:9006').split(',');
const MODE = __ENV.MODE || 'lua';
const TOTAL = Number(__ENV.TOTAL || 500);
const LIMIT = Number(__ENV.LIMIT || 50);
const KEY = __ENV.KEY || `${MODE}-${Date.now()}`;

const allowed = new Counter('allowed');
const rejected = new Counter('rejected');
const errors = new Counter('errors');

export const options = {
  scenarios: { burst: { executor: 'shared-iterations', vus: Number(__ENV.VUS || 50), iterations: TOTAL, maxDuration: '5m' } },
};

export default function () {
  const node = NODES[exec.scenario.iterationInTest % NODES.length];
  const res = http.post(`${node}/api/ticket-service/bench/rate-limit/acquire?key=${KEY}&limit=${LIMIT}&windowMs=60000&mode=${MODE}`);
  if (res.status !== 200 || res.json('code') !== '0') { errors.add(1); return; }
  if (res.json('data') === true) allowed.add(1); else rejected.add(1);
}

export function handleSummary(data) {
  const v = (n) => (data.metrics[n] ? data.metrics[n].values.count : 0);
  return { stdout: [MODE, TOTAL, LIMIT, v('allowed'), v('rejected'), v('errors')].join('\t') + '\n' };
}
