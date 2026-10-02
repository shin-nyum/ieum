package kr.ieum.app;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDialog;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import java.io.ByteArrayInputStream;
import java.util.Collections;
import java.util.Locale;
import org.json.JSONObject;

/**
 * 모바일 청첩장 뷰어 — 하객이 이음 초대 링크로 들어오면 외부 모청 페이지를 앱 안에서 바로 보여 준다(1.4.2+).
 *  - 모청 사이트 대부분이 iframe 삽입을 막아(X-Frame-Options) 웹으로는 감쌀 수 없어서, 별도 WebView를 전체 화면 다이얼로그로 띄운다.
 *  - 계좌 복사 감지: 문서 시작 스크립트로 navigator.clipboard.writeText · execCommand('copy') · copy 이벤트를 '관찰'만 하고
 *    (사용자 탭 직후·실제 이벤트만) 메인 프레임의 보고만 받는다. 다이얼로그가 떠 있는 동안 클립보드 변경 리스너(길게 눌러 '복사' 포함)로 보강.
 *    받은 원문은 그대로 웹으로 넘기고, 계좌인지 판별·명부 기록 흐름은 웹(OTA로 고칠 수 있는 쪽)이 맡는다.
 *  - 모청 안의 외부 링크(토스·카카오페이 송금, 지도, 전화, 카톡 공유)는 이음 플러그인의 외부 앱 열기로 넘기고, 무엇으로 나갔는지(kind)를 웹에 알린다
 *    — 송금 앱이면 기록 흐름, 지도·전화면 돌아와도 '보내셨나요?'를 묻지 않게.
 *  - 이음 자신의 주소(shin-nyum.github.io)는 뷰어에서 열지 않는다(같은 출처 저장소를 공유해 기록이 섞일 수 있음) → 앱 링크로 넘김.
 *  - 창마다 Session 하나: dismiss 콜백은 메시지 큐로 늦게 오므로, 닫자마자 다시 열어도 새 창을 지우지 않게 세션 단위로 정리한다.
 */
final class InvitationViewer {

    interface Host {
        void onCopy(String text, String via, String tag);
        /** 뷰어에서 외부 앱·브라우저로 나가기 직전. kind = pay|map|contact|share|store|own|other, app = toss|kakao|"" */
        void onLeave(String kind, String app, String url, String tag);
        void onClosed(String tag, String reason);
        void openExternal(Uri uri);
    }

    static final String OWN_HOST = "shin-nyum.github.io";
    private static final int INK = 0xFF191919, INK2 = 0xFF444444, MUTED = 0xFF8A8A8A, LINE = 0xFFE5E5E5, ACCENT = 0xFF8F6852;
    private static final int MAX_COPY = 300;

    /** 뷰어 안이 아니라 앱(App Links)·브라우저로 보낼 호스트: 지도·길찾기, 간편송금 링크. */
    private static final String[] EXTERNAL_HOSTS = {
        "map.naver.com", "m.map.naver.com", "naver.me", "map.kakao.com", "m.map.kakao.com", "kko.to",
        "maps.google.com", "maps.app.goo.gl", "tmap.co.kr",
        "kakaopay.com", "toss.me", "toss.im"
    };
    private static final String[] MAP_HOSTS = {
        "map.naver.com", "m.map.naver.com", "naver.me", "map.kakao.com", "m.map.kakao.com", "kko.to", "maps.google.com", "maps.app.goo.gl", "tmap.co.kr"
    };

    /** 문서 시작 스크립트 — 페이지·iframe마다 1회. 복사는 관찰만(페이지 동작 그대로), 숨겨지면 재생 중인 미디어 정지. */
    static final String HOOK =
        "(function(){if(window.__ieumCopyHook)return;window.__ieumCopyHook=1;"
        + "var B=window.IeumCopy;"
        + "var post=function(t,v){try{if(!B)return;if(typeof B.postMessage==='function')B.postMessage(JSON.stringify({t:t,v:v}));else if(B.report)B.report(t,v);}catch(e){}};"
        + "var act=function(){try{var u=navigator.userActivation;return !u||u.isActive;}catch(e){return true;}};"
        + "var send=function(t,v){try{t=String(t==null?'':t);if(t&&t.length<=" + MAX_COPY + ")post(t,v);}catch(e){}};"
        + "var selText=function(){var s='';try{var a=document.activeElement;"
        + "if(a&&(a.tagName==='TEXTAREA'||a.tagName==='INPUT')){try{s=String(a.value).substring(a.selectionStart,a.selectionEnd);}catch(e){}if(!s)s=String(a.value||'');}"
        + "if(!s){var g=window.getSelection&&window.getSelection();s=g?String(g):'';}}catch(e){}return s;};"
        + "try{var cb=navigator.clipboard;if(cb&&cb.writeText){var ow=cb.writeText.bind(cb);"
        + "cb.writeText=function(t){if(act())send(t,'writeText');return ow(t);};}}catch(e){}"
        + "try{var oe=Document.prototype.execCommand;Document.prototype.execCommand=function(c){"
        + "try{if(String(c).toLowerCase()==='copy'&&act()){var s=selText();if(s)send(s,'exec');}}catch(e){}"
        + "return oe.apply(this,arguments);};}catch(e){}"
        + "try{window.addEventListener('copy',function(ev){if(!ev.isTrusted)return;var s='';try{var d=ev.clipboardData&&ev.clipboardData.getData('text/plain');if(d)s=d;}catch(e){}"
        + "if(!s)s=selText();if(s)send(s,'event');},false);}catch(e){}"
        + "try{var M=[];var op=HTMLMediaElement.prototype.play;HTMLMediaElement.prototype.play=function(){try{if(M.indexOf(this)<0)M.push(this);}catch(e){}return op.apply(this,arguments);};"
        + "window.__ieumPause=function(){try{document.querySelectorAll('audio,video').forEach(function(x){try{x.pause();}catch(e){}});}catch(e){}"
        + "M.forEach(function(x){try{x.pause();}catch(e){}});};"
        + "document.addEventListener('visibilitychange',function(){if(document.hidden)window.__ieumPause();});}catch(e){}"
        + "})();";

    static final String PAUSE_MEDIA =
        "(function(){try{if(window.__ieumPause)window.__ieumPause();else document.querySelectorAll('audio,video').forEach(function(m){try{m.pause();}catch(e){}});}catch(e){}})();";

    private final AppCompatActivity activity;
    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Session current = null;

    InvitationViewer(AppCompatActivity activity, Host host) {
        this.activity = activity;
        this.host = host;
    }

    boolean isShowing() {
        return current != null && current.dialog != null && current.dialog.isShowing();
    }

    String currentTag() {
        return isShowing() ? current.tag : "";
    }

    /** UI 스레드에서 호출. 이미 떠 있으면 같은 창에 새 주소를 연다. fallback = 열기 실패 시 '브라우저로 열기'에 쓸 원래 주소. */
    void open(String url, String title, String tag, String fallback) {
        if (!isShowing()) current = new Session();
        Session s = current;
        s.tag = tag == null ? "" : tag;
        s.fallback = TextUtils.isEmpty(fallback) ? url : fallback;
        s.lastText = null;
        s.titleView.setText(TextUtils.isEmpty(title) ? "모바일 청첩장" : title);
        s.setHost(url);
        s.web.loadUrl(url);
        if (!s.dialog.isShowing()) s.dialog.show();
    }

    /** UI 스레드에서 호출. */
    void close() {
        Session s = current;
        if (s != null && s.dialog != null) {
            try { s.dialog.dismiss(); } catch (Exception ignored) {}
        }
    }

    void notice(String text) {
        if (!isShowing() || TextUtils.isEmpty(text)) return;
        Toast.makeText(activity, text, Toast.LENGTH_LONG).show();
    }

    void onPause() {
        Session s = current;
        if (s != null) s.paused = true;
        if (s == null || s.web == null) return;
        try { s.web.evaluateJavascript(PAUSE_MEDIA, null); } catch (Exception ignored) {}
        try { s.web.onPause(); } catch (Exception ignored) {}
    }

    void onResume() {
        Session s = current;
        if (s != null) s.paused = false;
        if (s == null || s.web == null) return;
        try { s.web.onResume(); } catch (Exception ignored) {}
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, activity.getResources().getDisplayMetrics()));
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    private static boolean hostIn(String h, String[] list) {
        for (String x : list) {
            if (h.equals(x) || h.endsWith("." + x)) return true;
        }
        return false;
    }

    /** 외부로 나가는 링크의 종류 — 웹이 '송금하러 감' / '지도·전화·공유라 송금 아님'을 가른다. */
    static String[] classify(Uri u) {
        String scheme = lower(u.getScheme());
        String h = lower(u.getHost());
        String s = lower(u.toString());
        if (scheme.equals("supertoss") || hostIn(h, new String[] { "toss.me", "toss.im" }) || s.contains("viva.republica.toss")) return new String[] { "pay", "toss" };
        if (hostIn(h, new String[] { "kakaopay.com" }) || s.startsWith("kakaotalk://kakaopay") || s.contains("com.kakaopay") || (scheme.equals("intent") && s.contains("kakaopay"))) return new String[] { "pay", "kakao" };
        if (scheme.equals("nmap") || scheme.equals("kakaomap") || scheme.equals("tmap") || hostIn(h, MAP_HOSTS)
            || (scheme.equals("intent") && (s.contains("nmap") || s.contains("kakaomap") || s.contains("tmap") || s.contains("com.google.android.apps.maps")))) return new String[] { "map", "" };
        if (scheme.equals("tel") || scheme.equals("sms") || scheme.equals("smsto") || scheme.equals("mailto")) return new String[] { "contact", "" };
        if (scheme.equals("kakaolink") || scheme.equals("kakaotalk") || (scheme.equals("intent") && s.contains("com.kakao.talk"))) return new String[] { "share", "" };
        if (scheme.equals("market") || h.equals("play.google.com")) return new String[] { "store", "" };
        return new String[] { "other", "" };
    }

    /** 창 하나 = 다이얼로그 + WebView + 클립보드 리스너. */
    private final class Session {
        final AppCompatDialog dialog;
        WebView web;
        final ProgressBar bar;
        final TextView titleView;
        final TextView hostView;
        final ClipboardManager clip;
        ClipboardManager.OnPrimaryClipChangedListener clipListener;
        boolean docStartHook = false;
        boolean msgListener = false;
        boolean crashed = false;
        boolean errorShown = false;
        boolean paused = false;   // 앱이 뒤로 간 동안(뱅킹앱 사용 중)의 클립보드 변경은 모청 복사가 아님 — 안드로이드 7~9는 백그라운드에도 알려 준다
        AlertDialog errDialog = null;
        String tag = "";
        String fallback = "";
        String lastText = null;
        long lastAt = 0;
        boolean torn = false;

        @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
        Session() {
            LinearLayout root = new LinearLayout(activity);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundColor(Color.WHITE);

            LinearLayout top = new LinearLayout(activity);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            top.setBackgroundColor(Color.WHITE);

            TextView back = new TextView(activity);
            back.setText("‹ 이음");
            back.setTextColor(INK);
            back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            back.setTypeface(Typeface.DEFAULT_BOLD);
            back.setGravity(Gravity.CENTER_VERTICAL);
            back.setPadding(dp(16), 0, dp(14), 0);
            back.setContentDescription("이음으로 돌아가기");
            back.setOnClickListener(v -> close());

            LinearLayout titles = new LinearLayout(activity);
            titles.setOrientation(LinearLayout.VERTICAL);
            titles.setPadding(0, 0, dp(16), 0);

            titleView = new TextView(activity);
            titleView.setTextColor(INK2);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            titleView.setTypeface(Typeface.DEFAULT_BOLD);
            titleView.setSingleLine(true);
            titleView.setEllipsize(TextUtils.TruncateAt.END);

            hostView = new TextView(activity);   // 지금 보고 있는 사이트 주소 — 이음 화면처럼 보이는 낯선 페이지에 속지 않게
            hostView.setTextColor(MUTED);
            hostView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            hostView.setSingleLine(true);
            hostView.setEllipsize(TextUtils.TruncateAt.MIDDLE);

            titles.addView(titleView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            titles.addView(hostView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            top.addView(back, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
            top.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            View divider = new View(activity);
            divider.setBackgroundColor(LINE);

            bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(100);
            bar.setIndeterminate(false);
            bar.setProgressTintList(ColorStateList.valueOf(ACCENT));
            bar.setVisibility(View.GONE);

            web = new WebView(activity);
            configureWeb();

            root.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
            root.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.5f))));
            root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));
            root.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            dialog = new AppCompatDialog(activity, androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar);
            dialog.setContentView(root);
            dialog.setCanceledOnTouchOutside(false);
            Window w = dialog.getWindow();
            if (w != null) {
                w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                w.setBackgroundDrawable(new ColorDrawable(Color.WHITE));
                w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
                w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
                w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                WindowCompat.setDecorFitsSystemWindows(w, false);
                try {
                    w.setStatusBarColor(Color.TRANSPARENT);
                    // 밝은 내비바 아이콘은 API 26부터 — 그 아래(7.x)는 흰 바탕에 흰 버튼이 묻히므로 검은 내비바
                    w.setNavigationBarColor(Build.VERSION.SDK_INT >= 26 ? Color.TRANSPARENT : Color.BLACK);
                } catch (Exception ignored) {}
                if (Build.VERSION.SDK_INT >= 28) {
                    WindowManager.LayoutParams lp = w.getAttributes();
                    lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                    w.setAttributes(lp);
                }
                WindowInsetsControllerCompat ic = WindowCompat.getInsetsController(w, w.getDecorView());
                ic.setAppearanceLightStatusBars(true);
                if (Build.VERSION.SDK_INT >= 26) ic.setAppearanceLightNavigationBars(true);
            }
            // 상태바·내비바·노치·키보드만큼 안쪽으로 (edge-to-edge 강제 기기 포함 동일하게)
            ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
                Insets sb = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
                Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
                v.setPadding(sb.left, sb.top, sb.right, Math.max(sb.bottom, ime.bottom));
                return WindowInsetsCompat.CONSUMED;
            });
            // 뒤로가기: 모청 안에서 뒤로 → 첫 페이지면 닫기 (targetSdk 36 예측형 뒤로가기는 ComponentDialog가 OnBackInvokedDispatcher로 연결)
            dialog.getOnBackPressedDispatcher().addCallback(dialog, new OnBackPressedCallback(true) {
                @Override
                public void handleOnBackPressed() {
                    if (web != null && web.canGoBack()) web.goBack();
                    else { try { dialog.dismiss(); } catch (Exception ignored) {} }
                }
            });
            dialog.setOnDismissListener(d -> teardown());

            clip = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clip != null) {
                clipListener = () -> {
                    try {
                        if (torn || paused || !dialog.isShowing()) return;
                        ClipData cd = clip.getPrimaryClip();
                        if (cd == null || cd.getItemCount() == 0) return;
                        CharSequence t = cd.getItemAt(0).coerceToText(activity);
                        if (t != null) report(t.toString(), "clip");
                    } catch (Exception ignored) {}
                };
                clip.addPrimaryClipChangedListener(clipListener);
            }
        }

        void setHost(String url) {
            try {
                String h = Uri.parse(url).getHost();
                hostView.setText(h == null ? "" : h);
            } catch (Exception ignored) {}
        }

        @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
        private void configureWeb() {
            WebSettings s = web.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setSupportMultipleWindows(false);            // target=_blank는 같은 뷰어에서
            s.setJavaScriptCanOpenWindowsAutomatically(false); // 탭 없는 window.open(팝언더 광고)은 막는다
            s.setMediaPlaybackRequiresUserGesture(true);   // 배경음악은 하객이 누른 뒤에만
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
            s.setUseWideViewPort(true);
            s.setLoadWithOverviewMode(true);
            s.setBuiltInZoomControls(true);
            s.setDisplayZoomControls(false);
            s.setAllowFileAccess(false);
            s.setAllowContentAccess(false);
            s.setGeolocationEnabled(false);
            try { CookieManager.getInstance().setAcceptThirdPartyCookies(web, true); } catch (Exception ignored) {}

            // 복사 보고 통로: 가능하면 WebMessageListener(메인 프레임 보고만 받음 — 광고 iframe이 가짜 복사를 못 만들게), 아니면 JavascriptInterface
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                try {
                    WebViewCompat.WebMessageListener ml = (view, message, sourceOrigin, isMainFrame, replyProxy) -> {
                        if (!isMainFrame || torn || message == null) return;
                        try {
                            JSONObject o = new JSONObject(String.valueOf(message.getData()));
                            report(o.optString("t", ""), o.optString("v", ""));
                        } catch (Exception ignored) {}
                    };
                    WebViewCompat.addWebMessageListener(web, "IeumCopy", Collections.singleton("*"), ml);
                    msgListener = true;
                } catch (Exception ex) {
                    msgListener = false;
                }
            }
            if (!msgListener) web.addJavascriptInterface(new CopyBridge(this), "IeumCopy");
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                try {
                    WebViewCompat.addDocumentStartJavaScript(web, HOOK, Collections.singleton("*"));
                    docStartHook = true;
                } catch (Throwable ignored) {}
            }

            web.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    return route(request.getUrl(), request.isForMainFrame(), request.hasGesture(), request.isRedirect());
                }

                @Override
                public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                    // 이음 자신(shin-nyum.github.io)은 앱과 같은 출처 저장소를 쓴다 — 모청이 iframe·자동 이동으로 띄워도 여기선 아무것도 불러오지 않는다
                    try {
                        Uri u = request.getUrl();
                        if (u != null && OWN_HOST.equals(lower(u.getHost()))) {
                            return new WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", null, new ByteArrayInputStream(new byte[0]));
                        }
                    } catch (Exception ignored) {}
                    return null;
                }

                @Override
                public void onPageStarted(WebView view, String url, Bitmap favicon) {
                    bar.setProgress(5);
                    bar.setVisibility(View.VISIBLE);
                    setHost(url);
                    if (!docStartHook) inject(view);
                }

                @Override
                public void onPageFinished(WebView view, String url) {
                    bar.setVisibility(View.GONE);
                    if (!docStartHook) inject(view);
                }

                @Override
                public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                    if (request == null || !request.isForMainFrame() || torn) return;
                    String d = error == null || error.getDescription() == null ? "" : error.getDescription().toString();
                    if (d.contains("ABORTED")) return;
                    showLoadError();
                }

                @Override
                public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                    // 렌더러가 죽은 WebView는 다시 쓸 수 없다 — false를 돌려주면 앱 전체가 종료되므로 이 창만 닫는다
                    crashed = true;
                    main.post(() -> { try { dialog.dismiss(); } catch (Exception ignored) {} });
                    return true;
                }
            });
            web.setWebChromeClient(new WebChromeClient() {
                @Override
                public void onProgressChanged(WebView view, int p) {
                    bar.setProgress(p);
                    bar.setVisibility(p >= 100 ? View.GONE : View.VISIBLE);
                }

                @Override
                public void onPermissionRequest(PermissionRequest request) {
                    try { request.deny(); } catch (Exception ignored) {}
                }

                @Override
                public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                    callback.invoke(origin, false, false);
                }

                @Override
                public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                    return false;
                }
            });
            web.setDownloadListener((url, ua, cd, mime, len) -> {
                String u = url == null ? "" : url;
                if (u.startsWith("blob:") || u.startsWith("data:")) {
                    notice("이 청첩장의 저장 기능은 브라우저에서 이용해 주세요");
                    return;
                }
                try { leaveTo(Uri.parse(u)); } catch (Exception ignored) {}
            });
        }

        private void inject(WebView view) {
            try { view.evaluateJavascript(HOOK, null); } catch (Exception ignored) {}
        }

        /** true = 뷰어가 처리하지 않음(외부로 넘기거나 막음). */
        private boolean route(Uri u, boolean mainFrame, boolean gesture, boolean redirect) {
            if (u == null || u.getScheme() == null) return false;
            String scheme = lower(u.getScheme());
            if (scheme.equals("http") || scheme.equals("https")) {
                String h = lower(u.getHost());
                if (h.equals(OWN_HOST)) {   // 이음 자신 — 같은 출처 저장소를 공유하므로 뷰어에서 열지 않고 앱 링크로
                    if (mainFrame) {
                        host.onLeave("own", "", u.toString(), tag);
                        host.openExternal(u);
                        close();
                    }
                    return true;
                }
                if (mainFrame && hostIn(h, EXTERNAL_HOSTS)) { leaveTo(u); return true; }
                return false;
            }
            if (scheme.equals("about") || scheme.equals("data") || scheme.equals("blob") || scheme.equals("javascript")) return false;
            if (scheme.equals("file") || scheme.equals("content")) return true; // 로컬 파일 접근 차단
            boolean allowed = mainFrame ? (gesture || redirect) : gesture;    // 탭 없이 앱을 띄우는 광고·자동 이동은 막는다
            if (allowed) leaveTo(u);
            return true;
        }

        private void leaveTo(Uri u) {
            String[] k = classify(u);
            String us = u.toString();
            host.onLeave(k[0], k[1], us.length() > 300 ? us.substring(0, 300) : us, tag);
            host.openExternal(u);
        }

        private void showLoadError() {
            if (errorShown || torn || !dialog.isShowing()) return;
            errorShown = true;
            try {   // 이 창(세션)에 묶인 대화상자 — 창이 닫히면 같이 닫히고, 버튼은 이 창만 다룬다
                errDialog = new AlertDialog.Builder(activity)
                    .setMessage("청첩장을 불러오지 못했어요.\n인터넷 연결을 확인하거나 브라우저로 열어 보세요.")
                    .setPositiveButton("다시 시도", (d, i) -> { errorShown = false; if (!torn && web != null) web.reload(); })
                    .setNeutralButton("브라우저로 열기", (d, i) -> {
                        errorShown = false;
                        if (torn) return;
                        try { host.openExternal(Uri.parse(fallback)); } catch (Exception ignored) {}
                        try { dialog.dismiss(); } catch (Exception ignored) {}
                    })
                    .setNegativeButton("닫기", (d, i) -> { errorShown = false; if (!torn) { try { dialog.dismiss(); } catch (Exception ignored) {} } })
                    .setOnCancelListener(d -> errorShown = false)
                    .show();
            } catch (Exception e) {
                errorShown = false;
            }
        }

        /** 복사 원문 전달 — 같은 글을 여러 경로(후킹·이벤트·클립보드)로 받으면 1.5초 안 중복은 한 번만. */
        void report(String text, String via) {
            if (torn || text == null) return;
            String t = text.trim();
            if (t.isEmpty() || t.length() > MAX_COPY) return;
            long now = System.currentTimeMillis();
            if (t.equals(lastText) && now - lastAt < 1500) return;
            lastText = t;
            lastAt = now;
            host.onCopy(t, via, tag);
        }

        private void teardown() {
            if (torn) return;
            torn = true;
            try { if (errDialog != null && errDialog.isShowing()) errDialog.dismiss(); } catch (Exception ignored) {}
            errDialog = null;
            try {
                if (clip != null && clipListener != null) clip.removePrimaryClipChangedListener(clipListener);
            } catch (Exception ignored) {}
            clipListener = null;
            if (web != null) {
                if (!crashed) {
                    try { web.evaluateJavascript(PAUSE_MEDIA, null); } catch (Exception ignored) {}
                    try { web.stopLoading(); } catch (Exception ignored) {}
                    try { web.loadUrl("about:blank"); } catch (Exception ignored) {}
                }
                try { ViewGroup p = (ViewGroup) web.getParent(); if (p != null) p.removeView(web); } catch (Exception ignored) {}
                try { if (!msgListener) web.removeJavascriptInterface("IeumCopy"); } catch (Exception ignored) {}
                try { web.destroy(); } catch (Exception ignored) {}
            }
            web = null;
            if (current == this) current = null;
            host.onClosed(tag, crashed ? "crash" : "");
        }
    }

    /** WebMessageListener를 못 쓰는 옛 WebView용 — 복사 원문 보고만(클립보드 쓰기 같은 권한은 주지 않는다). */
    private final class CopyBridge {
        private final Session session;

        CopyBridge(Session session) {
            this.session = session;
        }

        @JavascriptInterface
        public void report(final String text, final String via) {
            main.post(() -> session.report(text, via == null ? "" : via));
        }
    }
}
