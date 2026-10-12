// 实验 4：异步关单链路
//   MODE=latency ：N 个用户各买 1 张 G39 一等座，不支付，等待延迟消息（压测时缩短为 1s）自动关单回滚
//   MODE=replay  ：N 个用户各买 1 张，然后对每个订单并发投递 REPLAY 次关单消息（redelivery / duplicate）
import http from 'k6/http';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

const NODES = (__ENV.NODES || 'http://127.0.0.1:9005,http://127.0.0.1:9006').split(',');
const MODE = __ENV.MODE || 'latency';
const USERS = Number(__ENV.USERS || 50);
const REPLAY = Number(__ENV.REPLAY || 5);
const REPLAY_MODE = __ENV.REPLAY_MODE || 'redelivery';

const consumed = new Counter('replay_consumed');
const rejected = new Counter('replay_rejected');
const purchased = new Counter('orders_created');

export const options = {
  setupTimeout: '10m',
  scenarios: {
    replay: { executor: 'shared-iterations', vus: MODE === 'replay' ? 20 : 1, iterations: MODE === 'replay' ? USERS : 1, maxDuration: '10m' },
  },
};

const pad = (n, w) => String(n).padStart(w, '0');

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
  return { 'Content-Type': 'application/json', userId: String(880000 + i), username: `bench_u${i}`, realName: `Bench${i}` };
}

function ensurePassenger(base, i) {
  let res = http.get(`${base}/api/user-service/passenger/query`, { headers: userHeaders(i) });
  let list = res.json('data') || [];
  if (!list.length) {
    http.post(`${base}/api/user-service/passenger/save`, JSON.stringify({
      realName: `Bench${i}`, idType: 0, idCard: idCard(i), discountType: 0, phone: `138${pad(i, 8)}`,
    }), { headers: userHeaders(i) });
    res = http.get(`${base}/api/user-service/passenger/query`, { headers: userHeaders(i) });
    list = res.json('data') || [];
  }
  if (!list.length) throw new Error(`bench_u${i} 创建乘车人失败：${res.body}`);
  return String(list[0].id);
}

export function setup() {
  const orders = [];
  for (let i = 1; i <= USERS; i++) {
    const node = NODES[i % NODES.length];
    const passengerId = ensurePassenger(NODES[0], i);
    const res = http.post(`${node}/api/ticket-service/ticket/purchase/v2`, JSON.stringify({
      trainId: '2', departure: '北京南', arrival: '杭州东', chooseSeats: [],
      passengers: [{ passengerId, seatType: 1 }],
    }), { headers: userHeaders(i), timeout: '60s' });
    const body = res.json();
    if (body.code !== '0') throw new Error(`bench_u${i} 购票失败：${body.message}`);
    orders.push(body.data.orderSn);
    purchased.add(1);
  }
  return { orders, purchasedAt: Date.now() };
}

export default function (data) {
  if (MODE !== 'replay') return;
  const orderSn = data.orders[exec.scenario.iterationInTest];
  const reqs = [];
  for (let k = 0; k < REPLAY; k++) {
    reqs.push(['POST', `${NODES[k % NODES.length]}/api/ticket-service/bench/delay-close/replay?orderSn=${orderSn}&mode=${REPLAY_MODE}`, null, { timeout: '60s' }]);
  }
  http.batch(reqs).forEach((r) => {
    const outcome = r.status === 200 ? r.json('data.outcome') : 'http_' + r.status;
    if (outcome === 'consumed') consumed.add(1); else rejected.add(1);
  });
}

export function handleSummary(data) {
  const m = data.metrics;
  const v = (n) => (m[n] ? m[n].values.count : 0);
  const line = [MODE, MODE === 'replay' ? REPLAY_MODE : '-', USERS, v('orders_created'), MODE === 'replay' ? REPLAY : '-', v('replay_consumed'), v('replay_rejected')].join('\t');
  const out = { stdout: `${line}\n` };
  if (__ENV.SUMMARY) out[__ENV.SUMMARY] = JSON.stringify(data, null, 2);
  return out;
}
