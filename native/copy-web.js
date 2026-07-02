// 리포 루트의 정적 앱을 www/로 복사 (로컬 번들 — App Review 4.2 완화: 원격 URL 로드 아님)
const fs = require('fs');
const path = require('path');
const ROOT = path.resolve(__dirname, '..');
const OUT = path.join(__dirname, 'www');

const FILES = [
  'index.html', 'memorial.html', 'privacy.html',
  'manifest.webmanifest', 'service-worker.js',
  'icon-192.png', 'icon-512.png', 'icon-maskable-512.png', 'apple-touch-icon.png',
  'og-image.png', 'og-memorial.png',
];
// 스플래시 전부 포함
const splashes = fs.readdirSync(ROOT).filter(f => /^splash-\d+x\d+\.png$/.test(f));

fs.rmSync(OUT, { recursive: true, force: true });
fs.mkdirSync(OUT, { recursive: true });
for (const f of [...FILES, ...splashes]) {
  const src = path.join(ROOT, f);
  if (fs.existsSync(src)) fs.copyFileSync(src, path.join(OUT, f));
  else console.warn('skip (missing):', f);
}
console.log('copied', FILES.length + splashes.length, 'files → www/');
