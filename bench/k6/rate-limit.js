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
// 按节点分别统计，证明两个应用节点都参与了请求、共享同一份计数
const reqA = new Counter('req_a');
const reqB = new Counter('req_b');
const allowedA = new Counter('allowed_a');
const allowedB = new Counter('allowed_b');

export const options = {
  scenarios: { burst: { executor: 'shared-iterations', vus: Number(__ENV.VUS || 50), iterations: TOTAL, maxDuration: '5m' } },
};

export default function () {
  const idx = exec.scenario.iterationInTest % NODES.length;
  const node = NODES[idx];
  (idx === 0 ? reqA : reqB).add(1);
  const res = http.post(`${node}/api/ticket-service/bench/rate-limit/acquire?key=${KEY}&limit=${LIMIT}&windowMs=60000&mode=${MODE}`);
  if (res.status !== 200 || res.json('code') !== '0') { errors.add(1); return; }
  if (res.json('data') === true) {
    allowed.add(1);
    (idx === 0 ? allowedA : allowedB).add(1);
  } else {
    rejected.add(1);
  }
}

export function handleSummary(data) {
  const v = (n) => (data.metrics[n] ? data.metrics[n].values.count : 0);
  return { stdout: [MODE, TOTAL, LIMIT, v('allowed'), v('rejected'), v('errors'), `${v('req_a')}/${v('allowed_a')}`, `${v('req_b')}/${v('allowed_b')}`].join('\t') + '\n' };
}
