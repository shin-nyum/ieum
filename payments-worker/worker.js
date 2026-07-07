/* 이음 호스트 패스 — 토스페이먼츠 결제 승인 서버 (Cloudflare Worker)
   배포: payments-worker/README.md 참조. 시크릿 키는 코드가 아니라 wrangler secret으로 주입. */
const ALLOW_ORIGIN = 'https://shin-nyum.github.io';
const AMOUNT = 4900; // 호스트 패스 정가 — 클라이언트 값 신뢰하지 않음

function json(obj, status, extra) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: {
      'Content-Type': 'application/json',
      'Access-Control-Allow-Origin': ALLOW_ORIGIN,
      'Access-Control-Allow-Methods': 'POST,OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type',
      ...(extra || {}),
    },
  });
}

export default {
  async fetch(req, env) {
    if (req.method === 'OPTIONS') return json({}, 204);
    const url = new URL(req.url);
    if (req.method !== 'POST' || url.pathname !== '/confirm')
      return json({ ok: false, message: 'ieum payments worker' }, 200);
    if (!env.TOSS_SECRET_KEY) return json({ ok: false, message: 'server not configured' }, 500);

    let body;
    try { body = await req.json(); } catch (e) { return json({ ok: false, message: 'bad json' }, 400); }
    const { paymentKey, orderId } = body || {};
    if (!paymentKey || !orderId || !/^ieum-/.test(orderId))
      return json({ ok: false, message: 'invalid order' }, 400);

    // 토스 승인 — 금액은 서버 고정값 사용(클라이언트 조작 무력화)
    const r = await fetch('https://api.tosspayments.com/v1/payments/confirm', {
      method: 'POST',
      headers: {
        Authorization: 'Basic ' + btoa(env.TOSS_SECRET_KEY + ':'),
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ paymentKey, orderId, amount: AMOUNT }),
    });
    const j = await r.json();
    if (!r.ok) return json({ ok: false, code: j.code, message: j.message || 'confirm failed' }, 402);

    return json({
      ok: true,
      orderId: j.orderId,
      approvedAt: j.approvedAt,
      receiptUrl: (j.receipt && j.receipt.url) || null,
    }, 200);
  },
};
