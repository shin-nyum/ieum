// 네트워크 우선(network-first) — 온라인이면 항상 최신을 보여주고, 오프라인일 때만 캐시로 폴백
const CACHE = 'ieum-v53';
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
