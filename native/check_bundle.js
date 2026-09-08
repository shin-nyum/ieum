// 배포 전 게이트: bundle.json이 실제 배포 파일과 일치하는지 확인.
// 어긋나면 네이티브 앱의 OTA가 매번 해시 검증에 실패해 조용히 구버전에 머문다(v95에서 실제 발생).
//   node native/check_bundle.js            → 로컬 파일과 대조 (불일치 시 exit 1)
//   node native/check_bundle.js --live     → 배포된 GitHub Pages 실물과도 대조
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { hashFor } = require('./bundle_hash.js');
const ROOT = path.resolve(__dirname, '..');
const b = JSON.parse(fs.readFileSync(path.join(ROOT, 'bundle.json'), 'utf8'));
const build = (fs.readFileSync(path.join(ROOT, 'index.html'), 'utf8').match(/const APP_BUILD='([^']+)'/) || [])[1];
let bad = 0;
if (b.build !== build) { console.error(`build 불일치: bundle.json=${b.build} index.html=${build}`); bad++; }
for (const f of b.files) {
  if (hashFor(path.join(ROOT, f.p)) !== f.h) { console.error(`해시 불일치: ${f.p}`); bad++; }
}
if (bad) { console.error('→ node native/make_bundle.js 를 다시 실행하고 커밋하세요.'); process.exit(1); }
console.log(`bundle.json 최신 (${b.build}, ${b.files.length}개 파일)`);

if (process.argv.includes('--live')) {
  const BASE = 'https://shin-nyum.github.io/ieum/';
  (async () => {
    let liveBad = 0;
    const man = await fetch(BASE + 'bundle.json?t=' + Date.now(), { cache: 'no-store' }).then(r => r.ok ? r.json() : null).catch(() => null);
    if (!man) { console.error('배포본 bundle.json 을 받지 못했습니다 (Pages 반영 대기 중일 수 있음)'); process.exit(1); }
    if (man.build !== b.build) { console.error(`배포본 build=${man.build} (로컬 ${b.build}) — Pages 반영 대기`); liveBad++; }
    for (const f of b.files) {
      const buf = await fetch(BASE + f.p + '?t=' + Date.now(), { cache: 'no-store' }).then(r => r.ok ? r.arrayBuffer() : null).catch(() => null);
      if (!buf) { console.error(`배포본 없음: ${f.p}`); liveBad++; continue; }
      const h = crypto.createHash('sha256').update(Buffer.from(buf)).digest('hex');
      if (h !== f.h) { console.error(`배포본 해시 불일치: ${f.p}`); liveBad++; }
    }
    if (liveBad) process.exit(1);
    console.log(`배포본도 일치 (${BASE})`);
  })();
}
