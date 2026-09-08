// OTA 매니페스트 해시 공용 로직.
// ⚠️ 윈도우 체크아웃(core.autocrlf=true)에서는 워킹트리가 CRLF지만, GitHub Pages가 서빙하는 것은
//    저장소에 저장된 LF 원본이다. 워킹트리를 그대로 해싱하면 체크아웃 상태에 따라 해시가 달라져
//    앱의 sha256 검증이 조용히 실패한다 → 텍스트 파일은 LF로 정규화한 뒤 해싱한다.
const fs = require('fs');
const crypto = require('crypto');
const TEXT = /\.(html?|js|json|css|txt|svg|webmanifest|md)$/i;

function bytesFor(file) {
  const b = fs.readFileSync(file);
  return TEXT.test(file) ? Buffer.from(b.toString('binary').split('\r\n').join('\n'), 'binary') : b;
}
function hashFor(file) {
  return crypto.createHash('sha256').update(bytesFor(file)).digest('hex');
}
module.exports = { bytesFor, hashFor, FILES: ['index.html', 'memorial.html', 'privacy.html', 'icon-192.png', 'icon-512.png'] };
