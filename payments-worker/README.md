# 이음 결제 승인 서버 (Cloudflare Worker) — 실과금 전환 가이드

호스트 패스 ₩4,900 실결제의 **승인(confirm)** 을 담당합니다. 금액은 서버 고정(4,900)이라 클라이언트 조작이 무력화됩니다. 무료 플랜(10만 req/일)으로 충분.

## 선행 조건
1. **사업자등록** (홈택스, 개인사업자 OK)
2. **토스페이먼츠 가입·심사** → 개발자센터에서 `live_ck_…`(클라이언트 키) / `live_sk_…`(시크릿 키) 발급

## 배포 (윈도우, 5분)
```bash
cd payments-worker
npx wrangler login                      # Cloudflare 계정(무료) 브라우저 로그인
npx wrangler secret put TOSS_SECRET_KEY # live_sk_… 붙여넣기 (테스트는 test_sk_…)
npx wrangler deploy                     # → https://ieum-pay.<계정>.workers.dev
```

## 앱 스위치 (index.html의 PAY 상수 3줄)
```js
const PAY={
  mode:'live',
  clientKey:'live_ck_XXXX',                                  // 토스 라이브 클라이언트 키
  confirmUrl:'https://ieum-pay.<계정>.workers.dev/confirm'
};
```
커밋·푸시하면 그대로 실과금 시작. (먼저 `test_sk_` 시크릿 + `mode:'test'`+confirmUrl로 승인까지 포함한 전체 리허설 가능)

## 검증 로직 요약
- 클라: 주문 생성 시 `ieum_pay_pending`(orderId·eventId·금액) 저장 → 복귀 시 **orderId·금액 대조**, live는 서버 승인 성공시에만 패스 개방
- 서버: `ieum-` 접두 orderId만 허용, **금액 서버 고정**, 시크릿은 wrangler secret 주입(리포에 없음)
- 미승인 결제는 토스가 자동 취소 → 고객 청구 없음
