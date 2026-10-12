// 实验 6 补充：限流接入真实查票接口 /api/ticket-service/ticket/query（非 bench 端点）
// 双节点交替、50 并发；开启时总放行数应 ≤ 跨越的 1s 窗口数 × PERMITS；PERMITS=0 为关闭时的回归检查
import http from 'k6/http';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

const NODES = (__ENV.NODES || 'http://127.0.0.1:9005,http://127.0.0.1:9006').split(',');
const TOTAL = Number(__ENV.TOTAL || 300);
const PERMITS = Number(__ENV.PERMITS || 0);
const FROM = __ENV.FROM || 'BJP';
const TO = __ENV.TO || 'HZH';

const allowed = new Counter('allowed');
const limited = new Counter('limited');
const errors = new Counter('errors');
const reqA = new Counter('req_a');
const reqB = new Counter('req_b');

export const options = {
  scenarios: { burst: { executor: 'shared-iterations', vus: Number(__ENV.VUS || 50), iterations: TOTAL, maxDuration: '5m' } },
};

function today() {
  const d = new Date();
  const pad = (n) => (n < 10 ? '0' + n : '' + n);
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

export default function () {
  const idx = exec.scenario.iterationInTest % NODES.length;
  (idx === 0 ? reqA : reqB).add(1);
  const res = http.get(`${NODES[idx]}/api/ticket-service/ticket/query?fromStation=${FROM}&toStation=${TO}&departureDate=${today()}`);
  const body = res.body || '';
  if (body.indexOf('查询过于频繁') >= 0) {
    limited.add(1);
  } else if (res.status === 200 && res.json('code') === '0') {
    allowed.add(1);
  } else {
    errors.add(1);
  }
}

export function handleSummary(data) {
  const v = (n) => (data.metrics[n] ? data.metrics[n].values.count : 0);
  const durS = data.state.testRunDurationMs / 1000;
  const windows = Math.ceil(durS) + 1;
  const lines = [
    `permits_per_second=${PERMITS} total=${TOTAL} nodeA_req=${v('req_a')} nodeB_req=${v('req_b')}`,
    `allowed=${v('allowed')} limited=${v('limited')} errors=${v('errors')} duration=${durS.toFixed(2)}s`,
  ];
  if (PERMITS > 0) {
    lines.push(`上界：最多跨 ${windows} 个 1s 窗口 × ${PERMITS} = ${windows * PERMITS}，实际放行 ${v('allowed')} -> ${v('allowed') <= windows * PERMITS ? '符合' : '超发'}`);
  } else {
    lines.push(`限流关闭：limited 应为 0 -> ${v('limited') === 0 ? '符合' : '异常'}`);
  }
  return { stdout: lines.join('\n') + '\n' };
}
