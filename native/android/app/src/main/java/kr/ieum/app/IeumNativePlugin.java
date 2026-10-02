package kr.ieum.app;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebView;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.WebViewListener;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.util.HashMap;
import java.util.Map;

/**
 * 이음 네이티브 보조 플러그인 (Capacitor 8)
 *  - 커스텀 스킴·intent:// 링크 처리 (카카오톡 공유·토스 송금·지도 앱)  ← WebView는 브라우저와 달리 이를 스스로 못 연다
 *  - 다른 앱에서 '공유'로 들어온 텍스트(ACTION_SEND) 회수  ← 청첩장 링크 가져오기
 *  - OTA 웹 번들 부팅 확인(webReady) / 앱 정보
 *  - 모바일 청첩장 뷰어(openInvitation/closeInvitation/invitationNotice) — 초대 링크로 들어온 하객에게 모청을 앱 안에서 바로 보여 주고,
 *    모청 속 계좌 복사를 invitationCopy 이벤트로 웹에 넘긴다(계좌 판별·명부 기록 흐름은 웹이 맡음). 1.4.2+
 */
@CapacitorPlugin(name = "IeumNative")
public class IeumNativePlugin extends Plugin {

    static final String PREFS = "IeumNative";
    static final String KEY_BOOT_PENDING = "bootPending";
    static final String KEY_ROLLED_BACK = "rolledBack";

    private static final Map<String, String> STORE_PKG = new HashMap<>();
    static {
        STORE_PKG.put("kakaolink", "com.kakao.talk");
        STORE_PKG.put("kakaotalk", "com.kakao.talk");
        STORE_PKG.put("supertoss", "viva.republica.toss");
        STORE_PKG.put("tmap", "com.skt.tmap.ku");
        STORE_PKG.put("nmap", "com.nhn.android.nmap");
        STORE_PKG.put("kakaomap", "net.daum.android.map");
    }

    private String pendingShareText = null;
    private InvitationViewer viewer = null;

    private final InvitationViewer.Host viewerHost = new InvitationViewer.Host() {
        @Override
        public void onCopy(String text, String via, String tag) {
            JSObject o = new JSObject();
            o.put("text", text);
            o.put("via", via);
            o.put("tag", tag);
            notifyListeners("invitationCopy", o, true);
        }

        @Override
        public void onLeave(String kind, String app, String url, String tag) {
            JSObject o = new JSObject();
            o.put("kind", kind);
            o.put("app", app);
            o.put("url", url);
            o.put("tag", tag);
            notifyListeners("invitationLeave", o, true);
        }

        @Override
        public void onClosed(String tag, String reason) {
            JSObject o = new JSObject();
            o.put("tag", tag);
            o.put("reason", reason);
            notifyListeners("invitationClosed", o, false);
        }

        @Override
        public void openExternal(Uri uri) {
            openOutside(uri);
        }
    };
    private String pendingShareSubject = null;

    /**
     * 공유받기(ACTION_SEND)는 여기 한 곳에서만 처리한다.
     * BridgeActivity.load()가 콜드스타트에도 onNewIntent(getIntent())를 호출하므로 이 경로로 다 들어온다.
     * (Plugin.load()에서 미리 꺼내면 여기서 빈손이 되어 이벤트가 발생하지 않는다 — 실제로 그 버그가 있었다.)
     * 이벤트는 retain=true로 보내 JS 리스너가 나중에 등록돼도 전달된다.
     */
    @Override
    protected void handleOnNewIntent(Intent intent) {
        super.handleOnNewIntent(intent);
        if (captureShare(intent)) {
            JSObject o = new JSObject();
            o.put("text", pendingShareText);
            o.put("subject", pendingShareSubject == null ? "" : pendingShareSubject);
            pendingShareText = null;   // 리스너가 유일한 소비자 (getShareText는 수동 폴백)
            pendingShareSubject = null;
            notifyListeners("shareText", o, true);
        }
    }

    private boolean captureShare(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return false;
        // EXTRA_TEXT/SUBJECT의 타입은 CharSequence — 서식 있는 텍스트(Spanned)를 보내는 앱이 있고,
        // getStringExtra는 그 경우 null을 돌려줘 공유가 통째로 무시된다.
        CharSequence cs = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        String t = cs == null ? null : cs.toString();
        if (t == null || t.trim().isEmpty()) return false;
        pendingShareText = t;
        CharSequence sub = intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT);
        pendingShareSubject = sub == null ? null : sub.toString();
        intent.removeExtra(Intent.EXTRA_TEXT); // 회전·재생성 시 재전달 방지
        intent.removeExtra(Intent.EXTRA_SUBJECT);
        return true;
    }

    @Override
    public void load() {
        super.load();
        try {
            bridge.addWebViewListener(new WebViewListener() {
                @Override
                public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                    // 모든 WebView가 렌더러 하나를 같이 쓴다 — 무거운 모청 페이지로 렌더러가 죽어도 앱이 통째로 꺼지지 않게 화면만 다시 만든다(기록은 저장소에 있음)
                    try {
                        getActivity().runOnUiThread(() -> {
                            try { if (viewer != null) viewer.close(); } catch (Throwable ignored) {}
                            try { getActivity().recreate(); } catch (Throwable ignored) {}
                        });
                    } catch (Throwable ignored) {}
                    return true;
                }
            });
        } catch (Throwable ignored) {}
    }

    @PluginMethod
    public void getShareText(PluginCall call) {
        JSObject o = new JSObject();
        o.put("text", pendingShareText == null ? "" : pendingShareText);
        o.put("subject", pendingShareSubject == null ? "" : pendingShareSubject);
        pendingShareText = null;
        pendingShareSubject = null;
        call.resolve(o);
    }

    @PluginMethod
    public void webReady(PluginCall call) {
        SharedPreferences p = getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        p.edit().putInt(KEY_BOOT_PENDING, 0).apply();
        call.resolve();
    }

    /** Play 인앱 리뷰 카드(앱을 떠나지 않고 별점). 실제 표시 여부는 Google 쿼터가 결정하며 앱은 알 수 없다 — 어떤 경우에도 resolve. */
    @PluginMethod
    public void requestReview(final PluginCall call) {
        try {
            final com.google.android.play.core.review.ReviewManager manager =
                com.google.android.play.core.review.ReviewManagerFactory.create(getContext());
            manager.requestReviewFlow().addOnCompleteListener(task -> {
                if (!task.isSuccessful()) { JSObject o = new JSObject(); o.put("launched", false); call.resolve(o); return; }
                getActivity().runOnUiThread(() ->
                    manager.launchReviewFlow(getActivity(), task.getResult()).addOnCompleteListener(done -> {
                        JSObject o = new JSObject(); o.put("launched", true); call.resolve(o);
                    }));
            });
        } catch (Throwable e) {
            JSObject o = new JSObject(); o.put("launched", false); call.resolve(o);
        }
    }

    @PluginMethod
    public void getInfo(PluginCall call) {
        JSObject o = new JSObject();
        try {
            PackageInfo pi = getContext().getPackageManager().getPackageInfo(getContext().getPackageName(), 0);
            o.put("versionName", pi.versionName == null ? "" : pi.versionName);
            o.put("versionCode", (int) (android.os.Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode));
        } catch (Exception e) {
            o.put("versionName", "");
            o.put("versionCode", 0);
        }
        String base = null;
        try { base = bridge.getServerBasePath(); } catch (Exception ignored) {}
        o.put("serverBasePath", base == null ? "" : base);
        SharedPreferences p = getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        o.put("rolledBack", p.getBoolean(KEY_ROLLED_BACK, false));
        p.edit().putBoolean(KEY_ROLLED_BACK, false).apply();
        o.put("filesDir", getContext().getFilesDir().getAbsolutePath());
        call.resolve(o);
    }

    /** 모바일 청첩장 뷰어 열기 — url은 http(s)만. 이미 떠 있으면 같은 창에서 새 주소. tag는 이벤트에 그대로 실려 돌아온다(행사 id). */
    @PluginMethod
    public void openInvitation(final PluginCall call) {
        final String url = call.getString("url", "");
        final String title = call.getString("title", "");
        final String tag = call.getString("tag", "");
        final String fallback = call.getString("fallback", "");
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) {
            call.reject("BAD_URL");
            return;
        }
        getActivity().runOnUiThread(() -> {
            try {
                if (viewer == null) viewer = new InvitationViewer(getActivity(), viewerHost);
                viewer.open(url, title, tag, fallback);
                JSObject o = new JSObject();
                o.put("opened", true);
                call.resolve(o);
            } catch (Throwable e) {
                call.reject("OPEN_FAILED");
            }
        });
    }

    @PluginMethod
    public void closeInvitation(final PluginCall call) {
        getActivity().runOnUiThread(() -> {
            try { if (viewer != null) viewer.close(); } catch (Throwable ignored) {}
            call.resolve();
        });
    }

    /** 뷰어 위에 짧은 안내(토스트) — 웹 화면은 뷰어에 가려 보이지 않으므로. */
    @PluginMethod
    public void invitationNotice(final PluginCall call) {
        final String text = call.getString("text", "");
        getActivity().runOnUiThread(() -> {
            try { if (viewer != null) viewer.notice(text); } catch (Throwable ignored) {}
            call.resolve();
        });
    }

    @PluginMethod
    public void invitationState(final PluginCall call) {
        getActivity().runOnUiThread(() -> {
            JSObject o = new JSObject();
            boolean open = viewer != null && viewer.isShowing();
            o.put("open", open);
            o.put("tag", open ? viewer.currentTag() : "");
            call.resolve(o);
        });
    }

    @Override
    protected void handleOnPause() {
        super.handleOnPause();
        try { if (viewer != null) viewer.onPause(); } catch (Throwable ignored) {}
    }

    @Override
    protected void handleOnResume() {
        super.handleOnResume();
        try { if (viewer != null) viewer.onResume(); } catch (Throwable ignored) {}
    }

    @Override
    protected void handleOnDestroy() {
        try { if (viewer != null) viewer.close(); } catch (Throwable ignored) {}
        viewer = null;
        super.handleOnDestroy();
    }

    /** 뷰어 안에서 누른 http(s)가 아닌 링크(또는 지도 등 바깥으로 보낼 http(s))를 외부 앱·브라우저로. */
    void openOutside(Uri url) {
        if (url == null || url.getScheme() == null) return;
        String scheme = url.getScheme().toLowerCase();
        if (scheme.equals("intent")) { openIntentUri(url.toString()); return; }
        if (scheme.equals("http") || scheme.equals("https")) { if (!viewUri(url)) notifyFail(url.toString(), scheme); return; }
        openScheme(url, scheme);
    }

    /** WebView 내비게이션 가로채기: http(s)·data·blob은 Capacitor 기본 정책, 나머지 스킴은 외부 앱으로. */
    @Override
    public Boolean shouldOverrideLoad(Uri url) {
        if (url == null || url.getScheme() == null) return null;
        String scheme = url.getScheme().toLowerCase();
        if (scheme.equals("http") || scheme.equals("https") || scheme.equals("data") || scheme.equals("blob")
            || scheme.equals("about") || scheme.equals("javascript") || scheme.equals("file")) return null;
        if (scheme.equals("intent")) { openIntentUri(url.toString()); return true; }
        openScheme(url, scheme);
        return true;
    }

    private void openIntentUri(String raw) {
        try {
            Intent in = Intent.parseUri(raw, Intent.URI_INTENT_SCHEME);
            // 웹 콘텐츠 유래 인텐트 하드닝: 브라우저블 컴포넌트만, 명시 컴포넌트·셀렉터 금지, 우리 파일에 대한 권한 부여 금지
            in.addCategory(Intent.CATEGORY_BROWSABLE);
            in.setComponent(null);
            in.setSelector(null);
            in.setFlags(in.getFlags() & ~(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION));
            in.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                getContext().startActivity(in);
                return;
            } catch (ActivityNotFoundException e) {
                String fb = in.getStringExtra("browser_fallback_url");
                if (fb != null && (fb.startsWith("http://") || fb.startsWith("https://"))) {
                    if (viewUri(Uri.parse(fb))) return;
                }
                String pkg = in.getPackage();
                if (pkg != null && !pkg.isEmpty()) openStore(pkg);
                else notifyFail(raw, "intent");
            }
        } catch (Exception e) {
            notifyFail(raw, "intent");
        }
    }

    private void openScheme(Uri url, String scheme) {
        if (viewUri(url)) return;
        String pkg = STORE_PKG.get(scheme);
        if (pkg != null) openStore(pkg); else notifyFail(url.toString(), scheme);
    }

    private boolean viewUri(Uri u) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, u);
            i.addCategory(Intent.CATEGORY_BROWSABLE);   // 브라우저처럼 '외부에서 열리도록 만들어진' 액티비티만
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void openStore(String pkg) {
        if (viewUri(Uri.parse("market://details?id=" + pkg))) return;
        viewUri(Uri.parse("https://play.google.com/store/apps/details?id=" + pkg));
    }

    private void notifyFail(String url, String scheme) {
        if (viewer != null && viewer.isShowing()) {
            final String m = "supertoss".equals(scheme) ? "토스 앱이 없어요 — 계좌번호를 복사해 보내 주세요"
                : ("kakaolink".equals(scheme) || "kakaotalk".equals(scheme)) ? "카카오톡을 열 수 없어요"
                : "연결된 앱을 열 수 없어요";
            getActivity().runOnUiThread(() -> { try { viewer.notice(m); } catch (Throwable ignored) {} });
            return;
        }
        JSObject o = new JSObject();
        o.put("url", url);
        o.put("scheme", scheme);
        notifyListeners("openFailed", o, true);
    }
}
