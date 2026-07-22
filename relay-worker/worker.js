/* 이음 응답 릴레이 — 하객의 '호스트에게 알리기'를 공유창 없이 호스트 이음으로 전달.
   설계: 사서함 키 = 행사 키의 SHA-256 해시(서버는 이름·행사 원문을 모름).
   내용물 = #g= 딥링크와 동일한 b64url 페이로드. 호스트가 수신(GET)하면 즉시 삭제(읽기 1회).
   저장: KV 네임스페이스 REPLIES, TTL 30일, 사서함당 최대 200건. */
const ORIGIN = 'https://shin-nyum.github.io';
const CORS = {
  'Access-Control-Allow-Origin': ORIGIN,
  'Access-Control-Allow-Methods': 'GET,POST,OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type',
};
const json = (obj, status = 200) =>
  new Response(JSON.stringify(obj), { status, headers: { 'Content-Type': 'application/json', ...CORS } });

export default {
  async fetch(req, env) {
    try {
      if (req.method === 'OPTIONS') return new Response(null, { status: 204, headers: CORS });
      const url = new URL(req.url);

      // 하객: 응답 넣기
      if (req.method === 'POST' && url.pathname === '/reply') {
        let body; try { body = await req.json(); } catch { return json({ error: 'BAD_JSON' }, 400); }
        const k = String(body.k || ''), code = String(body.code || '');
        if (!/^[0-9a-f]{32}$/.test(k)) return json({ error: 'BAD_KEY' }, 400);
        if (!/^[A-Za-z0-9_-]{20,2000}$/.test(code)) return json({ error: 'BAD_CODE' }, 400);
        const cur = JSON.parse((await env.REPLIES.get(k)) || '[]');
        if (cur.length >= 200) return json({ error: 'FULL' }, 429);
        if (!cur.includes(code)) cur.push(code); // 같은 내용 재전송은 멱등
        await env.REPLIES.put(k, JSON.stringify(cur), { expirationTtl: 60 * 60 * 24 * 30 });
        return json({ ok: true });
      }

      // 호스트: 응답 꺼내기 (읽기 1회 — 반환 후 삭제)
      if (req.method === 'GET' && url.pathname === '/replies') {
        const k = String(url.searchParams.get('k') || '');
        if (!/^[0-9a-f]{32}$/.test(k)) return json({ error: 'BAD_KEY' }, 400);
        const raw = await env.REPLIES.get(k);
        if (!raw) return json([]);
        await env.REPLIES.delete(k);
        return json(JSON.parse(raw));
      }

      // 기기 이전: 압축 백업 넣기 (TTL 7일, 읽기 1회)
      if (req.method === 'POST' && url.pathname === '/backup') {
        let body; try { body = await req.json(); } catch { return json({ error: 'BAD_JSON' }, 400); }
        const k = String(body.k || ''), j = String(body.j || '');
        if (!/^[a-z0-9]{8,24}$/.test(k)) return json({ error: 'BAD_KEY' }, 400);
        if (!/^[A-Za-z0-9_-]{20,2000000}$/.test(j)) return json({ error: 'BAD_DATA' }, 400);
        await env.REPLIES.put('bk:' + k, j, { expirationTtl: 60 * 60 * 24 * 7 });
        return json({ ok: true });
      }
      if (req.method === 'GET' && url.pathname === '/backup') {
        const k = String(url.searchParams.get('k') || '');
        if (!/^[a-z0-9]{8,24}$/.test(k)) return json({ error: 'BAD_KEY' }, 400);
        const j = await env.REPLIES.get('bk:' + k);
        if (!j) return json({});
        await env.REPLIES.delete('bk:' + k);
        return json({ j });
      }

      // 청첩장 썸네일 프록시 — 카카오 스크레이퍼를 차단하는 CDN 우회 (이미지 전용 · 실패 시 기본 이미지로 리다이렉트)
      if (req.method === 'GET' && url.pathname === '/img') {
        const FALLBACK = 'https://shin-nyum.github.io/ieum/og-image2.jpg';
        const u = String(url.searchParams.get('u') || '');
        if (!/^https:\/\/[^\s]{8,600}$/.test(u)) return Response.redirect(FALLBACK, 302);
        try {
          const r = await fetch(u, {
            headers: { 'User-Agent': 'Mozilla/5.0 (Linux; Android 14; SM-S926N) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Mobile Safari/537.36', 'Accept': 'image/*,*/*;q=0.8' },
            cf: { cacheTtl: 86400, cacheEverything: true },
          });
          const ct = r.headers.get('content-type') || '';
          const len = parseInt(r.headers.get('content-length') || '0', 10);
          if (!r.ok || !ct.startsWith('image/') || len > 4000000) return Response.redirect(FALLBACK, 302);
          return new Response(r.body, { headers: { 'Content-Type': ct, 'Cache-Control': 'public, max-age=86400', ...CORS } });
        } catch (e) { return Response.redirect(FALLBACK, 302); }
      }

      // 초대 오픈 카운터 (익명 — 해시 키+숫자만, 대규모 발송 게이트용)
      if (req.method === 'POST' && url.pathname === '/open') {
        let body; try { body = await req.json(); } catch { return json({ error: 'BAD_JSON' }, 400); }
        const k = String(body.k || '');
        if (!/^[0-9a-f]{32}$/.test(k)) return json({ error: 'BAD_KEY' }, 400);
        const cur = parseInt((await env.REPLIES.get('op:' + k)) || '0', 10);
        await env.REPLIES.put('op:' + k, String(cur + 1), { expirationTtl: 60 * 60 * 24 * 180 });
        return json({ ok: true });
      }
      if (req.method === 'GET' && url.pathname === '/open') {
        const k = String(url.searchParams.get('k') || '');
        if (!/^[0-9a-f]{32}$/.test(k)) return json({ error: 'BAD_KEY' }, 400);
        return json({ n: parseInt((await env.REPLIES.get('op:' + k)) || '0', 10) });
      }

      return json({ error: 'NOT_FOUND' }, 404);
    } catch (e) {
      return json({ error: 'INTERNAL', detail: String(e && e.message || e) }, 500);
    }
  },
};
