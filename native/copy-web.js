// 리포 루트의 정적 앱을 www/로 복사 — 네이티브(안드로이드·iOS) 번들.
// 레이아웃: www/index.html = /ieum/ 로 보내는 스텁, www/ieum/* = 실제 앱.
// 이유: Capacitor는 https://shin-nyum.github.io/ 오리진으로 www를 서빙하므로, 앱 내 링크·카카오 도메인·
// 초대링크(#i=/#e=)가 실사이트(https://shin-nyum.github.io/ieum/index.html)와 글자 단위로 같아진다.
const fs = require('fs');
const path = require('path');
const ROOT = path.resolve(__dirname, '..');
const OUT = path.join(__dirname, 'www');
const APP = path.join(OUT, 'ieum');

const FILES = [
  'index.html', 'memorial.html', 'privacy.html',
  'manifest.webmanifest',
  'icon-192.png', 'icon-512.png', 'icon-maskable-512.png', 'apple-touch-icon.png',
  'og-image3.jpg', 'og-memorial3.jpg',
];
const splashes = fs.readdirSync(ROOT).filter(f => /^splash-\d+x\d+\.png$/.test(f));

// fs.rmSync(recursive)는 일부 샌드박스 셸에서 프로세스를 무음 종료시킴 → 수동 재귀 삭제
function rmrf(p) {
  if (!fs.existsSync(p)) return;
  for (const e of fs.readdirSync(p, { withFileTypes: true })) {
    const q = path.join(p, e.name);
    if (e.isDirectory()) rmrf(q); else fs.unlinkSync(q);
  }
  fs.rmdirSync(p);
}
rmrf(OUT);
fs.mkdirSync(APP, { recursive: true });
let n = 0;
for (const f of [...FILES, ...splashes]) {
  const src = path.join(ROOT, f);
  if (fs.existsSync(src)) { fs.copyFileSync(src, path.join(APP, f)); n++; }
  else console.warn('skip (missing):', f);
}
fs.writeFileSync(path.join(OUT, 'index.html'), require('./web-stub.js').STUB);
const build = (fs.readFileSync(path.join(ROOT, 'index.html'), 'utf8').match(/const APP_BUILD='([^']+)'/) || [])[1] || '?';
console.log(`copied ${n} files -> www/ieum/ (+ root stub) - web build ${build}`);
