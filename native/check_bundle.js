// 배포 전 게이트: bundle.json이 실제 배포 파일과 일치하는지 확인.
// 어긋나면 네이티브 앱의 OTA가 매번 해시 검증에 실패해 조용히 구버전에 머문다(v95에서 실제 발생).
//   node native/check_bundle.js   → 불일치 시 exit 1
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const ROOT = path.resolve(__dirname, '..');
const b = JSON.parse(fs.readFileSync(path.join(ROOT, 'bundle.json'), 'utf8'));
const build = (fs.readFileSync(path.join(ROOT, 'index.html'), 'utf8').match(/const APP_BUILD='([^']+)'/) || [])[1];
let bad = 0;
if (b.build !== build) { console.error(`build 불일치: bundle.json=${b.build} index.html=${build}`); bad++; }
for (const f of b.files) {
  const h = crypto.createHash('sha256').update(fs.readFileSync(path.join(ROOT, f.p))).digest('hex');
  if (h !== f.h) { console.error(`해시 불일치: ${f.p}`); bad++; }
}
if (bad) { console.error('→ node native/make_bundle.js 를 다시 실행하고 커밋하세요.'); process.exit(1); }
console.log(`bundle.json 최신 (${b.build}, ${b.files.length}개 파일)`);
