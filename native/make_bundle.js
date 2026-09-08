// OTA 웹 번들 매니페스트 생성 — 리포 루트 bundle.json (GitHub Pages: /ieum/bundle.json)
// 배포 절차: index.html의 APP_BUILD 올리기 → node native/make_bundle.js → 커밋·푸시.
// 네이티브 앱이 릴레이(/web?p=)로 받아 sha256 검증 후 files/web/<build>/ 에 저장하고 다음 부팅부터 사용.
const fs = require('fs');
const path = require('path');
const { hashFor, bytesFor, FILES } = require('./bundle_hash.js');
const ROOT = path.resolve(__dirname, '..');
const build = (fs.readFileSync(path.join(ROOT, 'index.html'), 'utf8').match(/const APP_BUILD='([^']+)'/) || [])[1];
if (!build) throw new Error('APP_BUILD not found in index.html');
const files = FILES.map(p => {
  const f = path.join(ROOT, p);
  return { p, h: hashFor(f), s: bytesFor(f).length };
});
const out = { build, gen: new Date().toISOString(), files };
fs.writeFileSync(path.join(ROOT, 'bundle.json'), JSON.stringify(out, null, 1) + '\n');
console.log('bundle.json', build, files.map(f => `${f.p}:${f.s}`).join(' '));
