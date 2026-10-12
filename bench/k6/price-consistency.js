// 实验 2a：多节点本地缓存一致性
// 在节点 A 调整票价（更新 DB -> 删 Redis -> 本地失效 + Pub/Sub 广播），
// 立即在节点 B 轮询查询接口，记录「A 返回 -> B 读到新价格」的耗时，以及期间读到旧价格的次数
import http from 'k6/http';
import { Counter, Trend, Rate } from 'k6/metrics';

const NODE_A = __ENV.NODE_A || 'http://127.0.0.1:9005';
const NODE_B = __ENV.NODE_B || 'http://127.0.0.1:9006';
const ORIGINAL_CENTS = Number(__ENV.ORIGINAL_CENTS);
const ROUNDS = Number(__ENV.ROUNDS || 100);
const TIMEOUT_MS = Number(__ENV.TIMEOUT_MS || 3000);
const TRAIN_ID = '2';
const DEPARTURE = '北京南';
const ARRIVAL = '杭州东';
const SEAT_TYPE = 1;

const propagation = new Trend('propagation_ms', true);
const staleReads = new Counter('stale_reads');
const firstReadFresh = new Rate('first_read_fresh');
const timeouts = new Counter('propagation_timeouts');

export const options = {
  scenarios: { consistency: { executor: 'per-vu-iterations', vus: 1, iterations: ROUNDS, maxDuration: '10m' } },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

function today() {
  const d = new Date();
  const pad = (n) => (n < 10 ? '0' + n : '' + n);
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

const queryUrl = `${NODE_B}/api/ticket-service/ticket/query?fromStation=BJP&toStation=HZH&departureDate=${today()}`;

function updatePrice(cents) {
  const res = http.post(`${NODE_A}/api/ticket-service/admin/train-station-price/update`,
    JSON.stringify({ trainId: TRAIN_ID, departure: DEPARTURE, arrival: ARRIVAL, seatType: SEAT_TYPE, price: cents }),
    { headers: { 'Content-Type': 'application/json' } });
  if (res.status !== 200 || res.json('code') !== '0') {
    throw new Error(`update price failed: ${res.status} ${res.body}`);
  }
}

function readPriceOnB() {
  const res = http.get(queryUrl);
  const train = res.json('data.trainList').find((t) => t.trainId === TRAIN_ID);
  return train.seatClassList.find((s) => s.type === SEAT_TYPE).price;
}

export function setup() {
  if (!ORIGINAL_CENTS) throw new Error('ORIGINAL_CENTS 未设置');
  // 两个节点都先查一次，确保 L1 已加载（否则测到的是冷启动回源而不是失效广播）
  http.get(queryUrl);
  http.get(queryUrl.replace(NODE_B, NODE_A));
}

export default function () {
  const round = __ITER + 1;
  const newCents = ORIGINAL_CENTS + round * 100;
  const expected = newCents / 100;
  // 先确认 B 当前缓存的是旧值
  readPriceOnB();
  updatePrice(newCents);
  const start = Date.now();
  let polls = 0;
  for (;;) {
    const price = readPriceOnB();
    polls++;
    if (Math.abs(price - expected) < 0.001) {
      propagation.add(Date.now() - start);
      firstReadFresh.add(polls === 1);
      break;
    }
    staleReads.add(1);
    if (Date.now() - start > TIMEOUT_MS) {
      timeouts.add(1);
      firstReadFresh.add(false);
      break;
    }
  }
}

export function teardown() {
  updatePrice(ORIGINAL_CENTS);
}

export function handleSummary(data) {
  const m = data.metrics;
  const p = m.propagation_ms ? m.propagation_ms.values : { avg: 0, 'p(50)': 0, 'p(99)': 0, max: 0 };
  const line = [
    ROUNDS,
    p.avg.toFixed(2), p['p(50)'].toFixed(2), p['p(99)'].toFixed(2), p.max.toFixed(2),
    m.stale_reads ? m.stale_reads.values.count : 0,
    m.first_read_fresh ? (m.first_read_fresh.values.rate * 100).toFixed(1) : '0',
    m.propagation_timeouts ? m.propagation_timeouts.values.count : 0,
  ].join('\t');
  const out = { stdout: `${line}\n` };
  if (__ENV.SUMMARY) out[__ENV.SUMMARY] = JSON.stringify(data, null, 2);
  return out;
}
