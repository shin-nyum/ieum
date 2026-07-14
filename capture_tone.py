# -*- coding: utf-8 -*-
"""톤앤매너 튜닝용 캡처: 주요 화면을 before/after로 저장 (usage: python capture_tone.py before|after)"""
import json, os, sys, threading, functools, http.server, socketserver
from playwright.sync_api import sync_playwright

ROOT = os.path.dirname(os.path.abspath(__file__))
TAG = sys.argv[1] if len(sys.argv) > 1 else "before"
OUT = os.path.join(ROOT, "shots_tone", TAG)
os.makedirs(OUT, exist_ok=True)
PORT = 5078

def serve():
    handler = functools.partial(http.server.SimpleHTTPRequestHandler, directory=ROOT)
    socketserver.TCPServer.allow_reuse_address = True
    with socketserver.TCPServer(("127.0.0.1", PORT), handler) as httpd:
        httpd.serve_forever()

threading.Thread(target=serve, daemon=True).start()

# capture_shots.py의 STATE 재사용
import importlib.util
spec = importlib.util.spec_from_file_location("cs", os.path.join(ROOT, "capture_shots.py"))
# capture_shots는 임포트 시 캡처를 실행하므로 STATE만 텍스트로 뽑지 않고 여기 최소 데이터셋을 직접 정의
def g(name, rel, side, amt=None, method=None, status='yes', count=1):
    pay = {"amount": amt, "source": ("app" if method in ("toss","kakao") else ("manual" if amt else None)),
           "method": method} if amt else {"amount": None, "source": None, "method": None}
    return {"id":"g"+name, "name":name, "relation":rel, "side":side,
            "rsvp":{"status":status,"count":count,"meal":True}, "pay":pay, "unregistered":False}

STATE = {
  "_v": 3,
  "events": [
    { "id":"h1","role":"host","type":"wedding","partyA":"김민준","partyB":"이서연",
      "subtitle":"저희 결혼합니다","date":"2026-09-12","time":"13:00",
      "venue":"더채플 앳 청담","address":"서울 강남구 청담동 123-4",
      "message":"서로 마주보며 다져온 사랑을\n이제 함께 한곳을 바라보며\n걸어가고자 합니다.",
      "externalUrl":"","thankCard":{"design":0,"msg":""},
      "accounts":[{"label":"신랑측","bank":"카카오뱅크","number":"3333-01-1234567","holder":"김민준","kakaopay":"https://qr.kakaopay.com/demo"},
                   {"label":"신부측","bank":"토스뱅크","number":"1000-12-3456789","holder":"이서연"}],
      "guests":[ g("박지훈","친구",0,100000,"toss"), g("최수아","직장",1,50000,"cash"),
                 g("정우성","직장",0,100000,"toss"), g("김하늘","친구",1,50000,"manual"),
                 g("이도현","가족",0,300000,"toss"), g("한지민","친척",1) ] },
    { "id":"w2","role":"guest","type":"wedding","partyA":"박서준","partyB":"김지우",
      "subtitle":"결혼합니다","date":"2026-07-04","time":"12:30",
      "venue":"그랜드 워커힐 서울","address":"서울 광진구 워커힐로 177",
      "message":"두 사람이 사랑으로 만나\n새로운 가정을 이룹니다.\n귀한 걸음 해주시면 감사하겠습니다.",
      "externalUrl":"https://example.com",
      "accounts":[{"label":"신랑측","bank":"국민","number":"123-45-6789012","holder":"박서준","kakaopay":"https://qr.kakaopay.com/demo2"},
                   {"label":"신부측","bank":"신한","number":"110-234-567890","holder":"김지우"}],
      "myGift":None,"myRelation":"친구","guests":[] },
    { "id":"m1","role":"guest","type":"memorial","partyA":"故 김영식","partyB":"",
      "subtitle":"삼가 고인의 명복을 빕니다","date":"2026-07-20","time":"09:00",
      "venue":"서울성모병원 장례식장 5호","address":"서울 서초구 반포대로 222",
      "message":"","externalUrl":"","accounts":[{"label":"상주측","bank":"농협","number":"302-1234-5678-91","holder":"김민수"}],
      "myGift":50000,"myRelation":"직장","guests":[] },
    { "id":"w5","role":"guest","type":"wedding","partyA":"신동엽","partyB":"박나래",
      "subtitle":"결혼합니다","date":"2026-03-14","time":"12:00","venue":"소노펠리체 컨벤션",
      "address":"","message":"","externalUrl":"","accounts":[],"myGift":150000,"myRelation":"친한 친구","guests":[] }
  ],
  "people": [
    { "id":"p2","name":"이수민","relation":"친구","items":[{"id":"i2","dir":"received","amount":100000,"label":"내 결혼식","date":"2025-05-11"},{"id":"i3","dir":"gave","amount":50000,"label":"돌잔치","date":"2026-02-20"}] },
    { "id":"p3","name":"김태영","relation":"직장","items":[{"id":"i4","dir":"received","amount":50000,"label":"내 결혼식","date":"2025-05-11"}] }
  ],
  "settings": {"name":"김민준","kakaoKey":"","premium":True},
  "meta": {"createdAt": 1780000000000, "lastBackupAt": 1782800000000, "dirty": 0, "tutDone": True},
  "ui": {"entered":True,"tab":"guest","hostEventId":"h1","guestView":"list","currentEventId":None,
          "guestSession":{"name":"","acctIdx":0},"theme":"light"}
}

SHOTS = [
  ("01_landing", "S.ui.entered=false; S.ui.theme='light'; applyTheme(); render();", ".land-logo"),
  ("02_list",    "S.ui.entered=true; S.ui.tab='guest'; S.ui.guestView='list'; render();", ".homehero"),
  ("03_detail",  "S.ui.tab='guest'; S.ui.guestView='detail'; S.ui.currentEventId='w2'; render();", ".hero"),
  ("04_reco",    "S.ui.tab='guest'; S.ui.guestView='reco'; S.ui.reco={kind:'wedding',rel:3,attending:true,venue:'normal',close:false,ctxEventId:null}; render();", ".reco-result"),
  ("05_ledger",  "S.ui.tab='guest'; S.ui.guestView='ledger'; render();", ".stats"),
  ("06_more",    "S.ui.tab='guest'; S.ui.guestView='more'; render();", ".more-item"),
  ("07_host",    "S.ui.tab='host'; S.ui.hostEventId='h1'; S.ui.hostSub=null; render();", ".btn-kakao"),
  ("08_dark",    "S.ui.theme='dark'; applyTheme(); S.ui.tab='guest'; S.ui.guestView='list'; render();", ".homehero"),
]

with sync_playwright() as p:
    browser = p.chromium.launch()
    page = browser.new_page(viewport={"width":430,"height":932}, device_scale_factor=2)
    page.add_init_script(f"localStorage.setItem('ieum_v3', JSON.stringify({json.dumps(STATE, ensure_ascii=False)}));")
    page.goto(f"http://127.0.0.1:{PORT}/index.html", wait_until="networkidle")
    page.add_style_tag(content="::-webkit-scrollbar{display:none} .toast{display:none!important}")
    page.wait_for_timeout(1200)  # 폰트 안정화
    for name, js, waitsel in SHOTS:
        page.evaluate(js)
        try: page.wait_for_selector(waitsel, timeout=4000)
        except Exception: pass
        page.wait_for_timeout(500)
        page.screenshot(path=os.path.join(OUT, name+".png"), full_page=True)
        print("captured", name)
    browser.close()
print("done →", OUT)
