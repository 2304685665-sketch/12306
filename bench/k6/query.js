// 车票查询压测：同一接口通过 version 参数切换 v1 / v2 / v3 / v3-static
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE || 'http://127.0.0.1:9005';
const VERSION = __ENV.VERSION || 'v1';
const FROM = __ENV.FROM || 'BJP';
const TO = __ENV.TO || 'HZH';
const EXPECT_TRAINS = __ENV.EXPECT_TRAINS === undefined ? -1 : Number(__ENV.EXPECT_TRAINS);

export const options = {
  scenarios: {
    query: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 50),
      duration: __ENV.DURATION || '30s',
    },
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

function today() {
  const d = new Date();
  const pad = (n) => (n < 10 ? '0' + n : '' + n);
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

const url = `${BASE}/api/ticket-service/bench/ticket/query?version=${VERSION}&fromStation=${FROM}&toStation=${TO}&departureDate=${today()}`;

export default function () {
  const res = http.get(url);
  check(res, {
    'business ok': (r) => {
      if (r.status !== 200) return false;
      const body = r.json();
      if (body.code !== '0') return false;
      return EXPECT_TRAINS < 0 || body.data.trainList.length === EXPECT_TRAINS;
    },
  });
}

export function handleSummary(data) {
  const m = data.metrics;
  const d = m.http_req_duration.values;
  const reqs = m.http_reqs.values.count;
  const qps = m.http_reqs.values.rate;
  const okRate = m.checks ? m.checks.values.rate : 0;
  const line = [VERSION, reqs, qps.toFixed(0), d.avg.toFixed(2), d['p(50)'].toFixed(2), d['p(95)'].toFixed(2), d['p(99)'].toFixed(2), (okRate * 100).toFixed(2)].join('\t');
  const out = { stdout: `${line}\n` };
  if (__ENV.SUMMARY) out[__ENV.SUMMARY] = JSON.stringify(data, null, 2);
  if (__ENV.TSV) out[__ENV.TSV] = `${line}\n`;
  return out;
}
