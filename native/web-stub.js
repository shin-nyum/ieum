// www 루트 스텁 — 네이티브 앱의 첫 로드(https://shin-nyum.github.io/)를 실제 앱 경로(/ieum/index.html)로 보낸다.
// 쿼리·해시(딥링크 #i=/#e=/#r=, ?shared_text=)를 그대로 넘긴다. OTA 번들에도 같은 스텁을 쓴다(index.html의 WEB_STUB와 동일 유지).
exports.STUB = '<!doctype html><html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>이음</title><style>html,body{margin:0;background:#f3efe8}</style><script>location.replace("/ieum/index.html"+location.search+location.hash)</script></head><body></body></html>';
