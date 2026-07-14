# 이음 응답 릴레이 (5분 배포)

'호스트에게 알리기'를 **공유창 없이** 호스트의 이음으로 바로 전달하는 우체통 서버.
결제 워커와 달리 **비밀키·사업자 불필요** — Cloudflare 무료 플랜으로 충분.

## 배포

```bash
cd relay-worker
npx wrangler kv namespace create REPLIES   # 출력된 id를 wrangler.toml에 붙여넣기
npx wrangler deploy                        # → https://ieum-relay.<계정>.workers.dev
```

## 앱 연결 (한 줄)

index.html의 `const RELAY={ url:'' }` 에 워커 주소를 넣고 커밋·푸시:

```js
const RELAY={ url:'https://ieum-relay.<계정>.workers.dev' };
```

비어 있으면 기존 #g= 링크 공유 폴백으로 동작 (아무 변화 없음).

## 동작·프라이버시

- 하객: POST /reply `{k, code}` — k=행사키의 SHA-256 해시 32자(서버는 이름·행사 원문을 모름),
  code=#g= 딥링크와 동일한 b64url 페이로드.
- 호스트: 앱 열 때 GET /replies?k= → 장부 자동 반영. **반환 즉시 서버에서 삭제(읽기 1회)**, 미수신분은 30일 후 자동 소멸.
- 릴레이 실패 시 하객 쪽은 자동으로 공유창 폴백.
- 개인정보처리방침(앱 내 DOC.privacy·privacy.html)에 임시 경유 고지 문구 반영됨.

## 검증

```bash
npx wrangler dev
curl -X POST localhost:8787/reply -H 'Content-Type: application/json' \
  -d '{"k":"0123456789abcdef0123456789abcdef","code":"eyJ0ZXN0IjoxfQ"}'
curl 'localhost:8787/replies?k=0123456789abcdef0123456789abcdef'   # ["eyJ0ZXN0IjoxfQ"]
curl 'localhost:8787/replies?k=0123456789abcdef0123456789abcdef'   # [] (읽기 1회 삭제 확인)
```
