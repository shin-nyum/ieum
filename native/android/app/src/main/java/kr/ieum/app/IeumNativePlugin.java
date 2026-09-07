package kr.ieum.app;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.util.HashMap;
import java.util.Map;

/**
 * 이음 네이티브 보조 플러그인 (Capacitor 8)
 *  - 커스텀 스킴·intent:// 링크 처리 (카카오톡 공유·토스 송금·지도 앱)  ← WebView는 브라우저와 달리 이를 스스로 못 연다
 *  - 다른 앱에서 '공유'로 들어온 텍스트(ACTION_SEND) 회수  ← 청첩장 링크 가져오기
 *  - OTA 웹 번들 부팅 확인(webReady) / 앱 정보
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
    private String pendingShareSubject = null;

    @Override
    public void load() {
        try { captureShare(getActivity().getIntent()); } catch (Exception ignored) {}
    }

    @Override
    protected void handleOnNewIntent(Intent intent) {
        super.handleOnNewIntent(intent);
        if (captureShare(intent)) {
            JSObject o = new JSObject();
            o.put("text", pendingShareText);
            o.put("subject", pendingShareSubject == null ? "" : pendingShareSubject);
            notifyListeners("shareText", o, true);
        }
    }

    private boolean captureShare(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return false;
        String t = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (t == null || t.trim().isEmpty()) return false;
        pendingShareText = t;
        pendingShareSubject = intent.getStringExtra(Intent.EXTRA_SUBJECT);
        intent.removeExtra(Intent.EXTRA_TEXT); // 회전·재생성 시 재전달 방지
        return true;
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
            // 웹 콘텐츠 유래 인텐트 하드닝: 브라우저블 컴포넌트만, 명시 컴포넌트·셀렉터 금지
            in.addCategory(Intent.CATEGORY_BROWSABLE);
            in.setComponent(null);
            in.setSelector(null);
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
        JSObject o = new JSObject();
        o.put("url", url);
        o.put("scheme", scheme);
        notifyListeners("openFailed", o, true);
    }
}
