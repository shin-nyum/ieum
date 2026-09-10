// 네트워크 우선(network-first) — 온라인이면 항상 최신을 보여주고, 오프라인일 때만 캐시로 폴백
const CACHE = 'ieum-v101';
const ASSETS = [
  './',
  './index.html',
  './memorial.html',
  './privacy.html',
  './install.html',
  './qr-install.png',
  './manifest.webmanifest',
  './splash-750x1334.png',
  './splash-828x1792.png',
  './splash-1179x2556.png',
  './splash-1242x2688.png',
  './splash-1320x2868.png',
  './splash-1125x2436.png',
  './splash-1170x2532.png',
  './splash-1206x2622.png',
  './splash-1284x2778.png',
  './splash-1290x2796.png',
  './og-image.png',
  './og-memorial.png',
  './icon-192.png',
  './icon-512.png',
  './icon-maskable-512.png',
  './apple-touch-icon.png'
];

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(ASSETS)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys().then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

/* ---- 전날·당일 알림: 주기 백그라운드 동기화(설치형 PWA/TWA) — 앱을 안 열어도 임박 경조사를 알림 ---- */
function nOpen() { return new Promise((res, rej) => { const q = indexedDB.open('ieum-db', 1);
  q.onupgradeneeded = () => { try { q.result.createObjectStore('kv'); } catch (e) {} };
  q.onsuccess = () => res(q.result); q.onerror = () => rej(q.error); }); }
function nGet(k) { return nOpen().then((db) => new Promise((res) => { const tx = db.transaction('kv', 'readonly');
  const rq = tx.objectStore('kv').get(k); rq.onsuccess = () => { db.close(); res(rq.result); }; rq.onerror = () => { db.close(); res(undefined); }; })).catch(() => undefined); }
function nSet(k, v) { return nOpen().then((db) => new Promise((res) => { const tx = db.transaction('kv', 'readwrite');
  tx.objectStore('kv').put(v, k); tx.oncomplete = () => { db.close(); res(true); }; tx.onerror = () => { db.close(); res(false); }; })).catch(() => false); }

function dueMessage(ev, days) { // 알림 사다리: D-7 예고 · D-1 전날 · D-0 당일 · D+1 감사 인사(주최자)
  const mem = ev.type === 'memorial';
  const nm = mem ? (ev.partyA || '') : (ev.partyA || '') + (ev.partyB ? '·' + ev.partyB : ''); // 부고는 고인만 (상주 병기 금지)
  const label = mem ? '' : (ev.type === 'hwahon' ? '화혼' : '결혼식');
  const when = (ev.date || '') + (ev.time ? ' ' + ev.time : '') + (ev.venue ? ' · ' + ev.venue : '');
  if (days === 7) { if (mem) return null; // 부고는 임박 알림만 (사다리 예고 없음)
    return { title: `일주일 뒤, ${nm}님의 ${label}이에요`, body: when + ' — 마음을 미리 준비해 보세요' }; }
  if (days === 1) return mem
    ? { title: `${nm}님 부고 — 내일 발인이에요`, body: when }
    : { title: `내일, ${nm}님의 ${label}이에요`, body: when + ' — 마음을 준비해 주세요' };
  if (days === 0) return mem
    ? { title: `${nm}님 부고 — 오늘 발인이에요`, body: when }
    : { title: `오늘, ${nm}님의 ${label}이에요`, body: when };
  if (days === -1) { if (ev.role !== 'host') return null; // 감사 인사 리마인드는 주최자만
    return mem
      ? { title: '조문해 주신 분들께 감사 인사를 전할 차례예요', body: '이음의 [감사 인사 보내기]에서 문구와 사진을 준비할 수 있어요' }
      : { title: `어제 함께해 주신 분들께 감사 인사를 보낼 차례예요`, body: '이음의 [감사 인사 보내기]에서 한 번에 준비할 수 있어요' }; }
  return null;
}
async function checkDueEvents() {
  try {
    if (Notification.permission !== 'granted') return;
    if ((await nGet('noti')) !== '1') return; // 페이지가 설정 토글을 미러링
    const raw = await nGet('state'); if (!raw) return;
    const evs = (JSON.parse(raw).events || []);
    const doneRaw = await nGet('notified'); const done = doneRaw ? JSON.parse(doneRaw) : {};
    const now = new Date(); const t0 = new Date(now.getFullYear(), now.getMonth(), now.getDate());
    const toShow = [];
    for (const ev of evs) {
      if (!ev || !ev.date || ev.demo || ev.tut || ev.draft || !(ev.partyA || '').trim()) continue; // 초안·무명 행사 제외
      const days = Math.round((new Date(ev.date + 'T00:00') - t0) / 86400000);
      if (days !== 7 && days !== 1 && days !== 0 && days !== -1) continue;
      const key = ev.id + ':' + ev.date + ':' + days;
      if (done[key]) continue;
      const msg = dueMessage(ev, days); if (!msg) continue;
      toShow.push({ ev, days, key, msg });
    }
    if (!toShow.length) return;
    for (const k in done) { if (Date.now() - done[k] > 45 * 86400000) delete done[k]; } // 오래된 발화 기록 청소
    for (const { ev, days, key, msg } of toShow) { // 표시 성공 후에만 기록 — 실패 시 다음 sync에서 재시도
      try {
        await self.registration.showNotification(msg.title, { body: msg.body, icon: './icon-192.png', badge: './icon-192.png', tag: 'ieum-due-' + ev.id + '-' + days, data: { evId: ev.id } });
        done[key] = Date.now();
      } catch (e) {}
    }
    await nSet('notified', JSON.stringify(done));
  } catch (e) {}
}
self.addEventListener('periodicsync', (e) => { if (e.tag === 'ieum-daily') e.waitUntil(checkDueEvents()); });
self.addEventListener('notificationclick', (e) => { e.notification.close();
  const evId = (e.notification.data && e.notification.data.evId) || '';
  e.waitUntil(clients.matchAll({ type: 'window', includeUncontrolled: true }).then((list) => {
    // 앱 창(index) 우선 — memorial.html/install.html 등 다른 페이지에 포커스 뺏기지 않게
    const app = list.find((c) => /(\/|index\.html)$/.test(new URL(c.url).pathname));
    if (app && 'focus' in app) { try { app.postMessage({ type: 'open-event', evId }); } catch (err) {} return app.focus(); }
    return clients.openWindow('./' + (evId ? '?ev=' + encodeURIComponent(evId) : '')); })); });

self.addEventListener('fetch', (e) => {
  const url = new URL(e.request.url);
  if (url.origin !== location.origin) return; // 외부(카카오 SDK 등)는 그대로 네트워크
  e.respondWith(
    fetch(e.request)
      .then((res) => {
        const copy = res.clone();
        caches.open(CACHE).then((c) => c.put(e.request, copy)).catch(() => {});
        return res;
      })
      .catch(() => caches.match(e.request).then((c) => c || caches.match('./index.html')))
  );
});
