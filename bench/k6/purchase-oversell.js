// 实验 2b：双节点并发抢票防超卖
// N 个不同用户（各 1 个乘车人）通过 A/B 两个节点交替抢 G39 北京南->杭州东 一等座（库存 140）
import http from 'k6/http';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';

const NODES = (__ENV.NODES || 'http://127.0.0.1:9005,http://127.0.0.1:9006').split(',');
const VERSION = __ENV.VERSION || 'v2';
const USERS = Number(__ENV.USERS || 500);
const VUS = Number(__ENV.VUS || 50);
const SEAT_TYPE = 1;

const success = new Counter('purchase_success');
const soldOut = new Counter('purchase_sold_out');
const otherFail = new Counter('purchase_other_fail');
const purchaseMs = new Trend('purchase_ms', true);

export const options = {
  setupTimeout: '10m',
  scenarios: {
    rush: { executor: 'shared-iterations', vus: VUS, iterations: USERS, maxDuration: '10m' },
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

const pad = (n, w) => String(n).padStart(w, '0');

// 生成校验位正确的 18 位身份证号（北京东城区 110101）
function idCard(i) {
  const month = 1 + (Math.floor(i / 1000) % 12);
  const day = 1 + (Math.floor(i / 12000) % 28);
  const body = `1101011990${pad(month, 2)}${pad(day, 2)}${pad(i % 1000, 3)}`;
  const w = [7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2];
  const codes = '10X98765432';
  let sum = 0;
  for (let k = 0; k < 17; k++) sum += Number(body[k]) * w[k];
  return body + codes[sum % 11];
}

function userHeaders(i) {
  return {
    'Content-Type': 'application/json',
    userId: String(880000 + i),
    username: `bench_u${i}`,
    realName: `Bench${i}`,
  };
}

export function setup() {
  const base = NODES[0];
  const users = [];
  const BATCH = 50;
  for (let start = 1; start <= USERS; start += BATCH) {
    const idx = [];
    for (let i = start; i < start + BATCH && i <= USERS; i++) idx.push(i);
    // 已有乘车人则复用，否则新增
    let res = http.batch(idx.map((i) => ['GET', `${base}/api/user-service/passenger/query`, null, { headers: userHeaders(i) }]));
    const toCreate = idx.filter((i, k) => !(res[k].json('data') || []).length);
    if (toCreate.length) {
      http.batch(toCreate.map((i) => ['POST', `${base}/api/user-service/passenger/save`, JSON.stringify({
        realName: `Bench${i}`, idType: 0, idCard: idCard(i), discountType: 0, phone: `138${pad(i, 8)}`,
      }), { headers: userHeaders(i) }]));
      res = http.batch(idx.map((i) => ['GET', `${base}/api/user-service/passenger/query`, null, { headers: userHeaders(i) }]));
    }
    idx.forEach((i, k) => {
      const list = res[k].json('data') || [];
      if (!list.length) throw new Error(`用户 bench_u${i} 创建乘车人失败：${res[k].body}`);
      users.push({ i, passengerId: String(list[0].id) });
    });
  }
  return { users };
}

export default function (data) {
  const n = exec.scenario.iterationInTest;
  const user = data.users[n];
  const node = NODES[n % NODES.length];
  const path = VERSION === 'v2' ? '/api/ticket-service/ticket/purchase/v2' : '/api/ticket-service/ticket/purchase';
  const res = http.post(`${node}${path}`, JSON.stringify({
    trainId: '2', departure: '北京南', arrival: '杭州东', chooseSeats: [],
    passengers: [{ passengerId: user.passengerId, seatType: SEAT_TYPE }],
  }), { headers: userHeaders(user.i), timeout: '60s' });
  purchaseMs.add(res.timings.duration);
  let body = {};
  try { body = res.json(); } catch (e) { body = { message: res.body }; }
  if (res.status === 200 && body.code === '0') {
    success.add(1);
  } else if (String(body.message || '').includes('余票')) {
    soldOut.add(1);
  } else {
    otherFail.add(1);
    if (Math.random() < 0.2) console.warn(`fail: ${res.status} ${body.message}`);
  }
}

export function handleSummary(data) {
  const m = data.metrics;
  const v = (name) => (m[name] ? m[name].values.count : 0);
  const d = m.purchase_ms.values;
  const seconds = data.state.testRunDurationMs / 1000;
  const line = [VERSION, USERS, v('purchase_success'), v('purchase_sold_out'), v('purchase_other_fail'),
    d.avg.toFixed(1), d['p(50)'].toFixed(1), d['p(95)'].toFixed(1), d['p(99)'].toFixed(1), (USERS / seconds).toFixed(1)].join('\t');
  const out = { stdout: `${line}\n` };
  if (__ENV.SUMMARY) out[__ENV.SUMMARY] = JSON.stringify(data, null, 2);
  return out;
}
