/* 이음 호스트 패스 — 토스페이먼츠 결제 승인/복구 서버 (Cloudflare Worker)
   POST /confirm  {paymentKey, orderId}      → 승인(금액 서버고정)
   GET  /recover?orderId=ieum-...            → 주문 조회 후 미승인이면 승인까지 (응답 유실·iOS 파티션 복구용)
   시크릿: npx wrangler secret put TOSS_SECRET_KEY */
const ALLOW_ORIGIN = 'https://shin-nyum.github.io';
const AMOUNT = 4900;

const CORS = {
  'Access-Control-Allow-Origin': ALLOW_ORIGIN,
  'Access-Control-Allow-Methods': 'GET,POST,OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type',
};
function json(obj, status) {
  return new Response(JSON.stringify(obj), {
    status, headers: { 'Content-Type': 'application/json', ...CORS },
  });
}
async function tossFetch(path, opts, secret) {
  const r = await fetch('https://api.tosspayments.com' + path, {
    ...opts,
    headers: { Authorization: 'Basic ' + btoa(secret + ':'), 'Content-Type': 'application/json', ...(opts.headers || {}) },
  });
  const text = await r.text();
  let body = null; try { body = JSON.parse(text); } catch (e) { /* 게이트웨이 HTML 등 */ }
  return { status: r.status, ok: r.ok, body };
}

export default {
  async fetch(req, env) {
    try {
      if (req.method === 'OPTIONS') return new Response(null, { status: 204, headers: CORS }); // 프리플라이트: null 바디 필수
      const url = new URL(req.url);
      if (!env.TOSS_SECRET_KEY) return json({ ok: false, message: 'server not configured' }, 500);

      // ---- 승인 ----
      if (req.method === 'POST' && url.pathname === '/confirm') {
        let b; try { b = await req.json(); } catch (e) { return json({ ok: false, final: true, message: 'bad json' }, 400); }
        const { paymentKey, orderId } = b || {};
        if (!paymentKey || !orderId || !/^ieum-/.test(orderId)) return json({ ok: false, final: true, message: 'invalid order' }, 400);
        const r = await tossFetch('/v1/payments/confirm', { method: 'POST', body: JSON.stringify({ paymentKey, orderId, amount: AMOUNT }) }, env.TOSS_SECRET_KEY);
        if (r.body === null) return json({ ok: false, retry: true, message: 'gateway error' }, 502);   // 재시도 가능
        if (!r.ok) {
          if (r.body.code === 'ALREADY_PROCESSED_PAYMENT') return json({ ok: true, paid: true, receiptUrl: null }, 200); // 멱등 처리
          return json({ ok: false, final: true, code: r.body.code, message: r.body.message || 'declined' }, 402);        // 확정 거절
        }
        return json({ ok: true, paid: true, orderId: r.body.orderId, approvedAt: r.body.approvedAt, receiptUrl: (r.body.receipt && r.body.receipt.url) || null }, 200);
      }

      // ---- 복구: 승인 응답 유실·설치앱 파티션 복귀 대비 ----
      if (req.method === 'GET' && url.pathname === '/recover') {
        const orderId = url.searchParams.get('orderId') || '';
        if (!/^ieum-/.test(orderId)) return json({ ok: false, final: true, message: 'invalid order' }, 400);
        const q = await tossFetch('/v1/payments/orders/' + encodeURIComponent(orderId), { method: 'GET' }, env.TOSS_SECRET_KEY);
        if (q.body === null) return json({ ok: false, retry: true, message: 'gateway error' }, 502);
        if (q.status === 404) return json({ ok: true, paid: false, final: true, status: 'NOT_FOUND' }, 200); // 결제창 이탈 등 — 청구 없음
        if (!q.ok) return json({ ok: false, retry: true, message: q.body.message || 'query failed' }, 502);
        const st = q.body.status;
        if (st === 'DONE') return json({ ok: true, paid: true, receiptUrl: (q.body.receipt && q.body.receipt.url) || null }, 200);
        if ((st === 'READY' || st === 'IN_PROGRESS') && q.body.paymentKey) { // 인증만 완료 → 승인 시도
          const c = await tossFetch('/v1/payments/confirm', { method: 'POST', body: JSON.stringify({ paymentKey: q.body.paymentKey, orderId, amount: AMOUNT }) }, env.TOSS_SECRET_KEY);
          if (c.body === null) return json({ ok: false, retry: true, message: 'gateway error' }, 502);
          if (c.ok) return json({ ok: true, paid: true, receiptUrl: (c.body.receipt && c.body.receipt.url) || null }, 200);
          if (c.body.code === 'ALREADY_PROCESSED_PAYMENT') return json({ ok: true, paid: true, receiptUrl: null }, 200);
          return json({ ok: true, paid: false, final: true, status: st, message: c.body.message }, 200);
        }
        const finalStates = ['CANCELED', 'PARTIAL_CANCELED', 'ABORTED', 'EXPIRED'];
        return json({ ok: true, paid: false, final: finalStates.includes(st), status: st }, 200);
      }

      return json({ ok: false, message: 'ieum payments worker' }, 200);
    } catch (e) {
      return json({ ok: false, retry: true, message: 'server error' }, 500); // 어떤 예외도 CORS 포함 JSON으로
    }
  },
};
