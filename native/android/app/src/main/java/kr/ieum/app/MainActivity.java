package kr.ieum.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebView;
import com.getcapacitor.BridgeActivity;
import com.getcapacitor.WebViewListener;

public class MainActivity extends BridgeActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 재생성(프로세스 종료 뒤 복귀·글자 크기 변경)이나 최근 앱에서 다시 열 때 안드로이드는 처음 받은 링크 인텐트를 그대로 다시 준다.
        // Capacitor는 그때마다 appUrlOpen을 다시 보내므로 같은 초대·모청이 또 열리고, 송금하고 돌아온 하객의 기록 흐름이 끊긴다 → 링크 데이터를 비운다.
        try {
            Intent in = getIntent();
            boolean fromHistory = in != null && (in.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0;
            boolean plain = in == null || (Intent.ACTION_MAIN.equals(in.getAction()) && in.getData() == null && (in.getExtras() == null || in.getExtras().isEmpty()));
            if ((savedInstanceState != null || fromHistory) && !plain) {   // 링크·공유·알림 클릭 인텐트 모두 — 한 번 처리한 것을 다시 처리하지 않게
                setIntent(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(getPackageName()));
            }
        } catch (Exception ignored) {}
        // 모든 WebView(메인·모청 뷰어)가 렌더러 하나를 같이 쓴다 — 무거운 모청 페이지로 렌더러가 죽어도 앱이 통째로 꺼지지 않게 화면만 다시 만든다(기록은 저장소에 있음).
        // Bridge 빌더에 넣어야 한다: 플러그인 load()에서 bridge.addWebViewListener로 넣으면 Bridge 생성 직후 setWebViewListeners가 목록을 바꿔 사라진다.
        bridgeBuilder.addWebViewListener(new WebViewListener() {
            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                runOnUiThread(() -> { try { recreate(); } catch (Throwable ignored) {} });
                return true;
            }
        });
        registerPlugin(IeumNativePlugin.class);
        registerPlugin(IeumBillingPlugin.class);
        super.onCreate(savedInstanceState);
    }

    /**
     * OTA 웹 번들 부팅 가드.
     * Capacitor는 CapWebViewSettings.serverBasePath 에 저장된 경로(다운로드한 웹 번들)를 자동 적용한다.
     * 그 번들이 부팅에 실패하면(JS가 IeumNative.webReady()를 못 부름) 두 번 연속 실패한 뒤(=세 번째 시작) 경로를 지워
     * APK에 내장된 번들(assets/public)로 되돌린다. 앱 버전이 바뀌면 Capacitor가 스스로 경로를 초기화한다.
     */
    @Override
    protected void load() {
        try {
            SharedPreferences cap = getSharedPreferences("CapWebViewSettings", MODE_PRIVATE);
            String path = cap.getString("serverBasePath", null);
            SharedPreferences mine = getSharedPreferences(IeumNativePlugin.PREFS, MODE_PRIVATE);
            if (path != null && !path.isEmpty()) {
                int pending = mine.getInt(IeumNativePlugin.KEY_BOOT_PENDING, 0);
                if (pending >= 2) {
                    cap.edit().putString("serverBasePath", "").apply();
                    mine.edit().putInt(IeumNativePlugin.KEY_BOOT_PENDING, 0).putBoolean(IeumNativePlugin.KEY_ROLLED_BACK, true).apply();
                } else {
                    mine.edit().putInt(IeumNativePlugin.KEY_BOOT_PENDING, pending + 1).apply();
                }
            } else {
                mine.edit().putInt(IeumNativePlugin.KEY_BOOT_PENDING, 0).apply();
            }
        } catch (Exception ignored) {}
        super.load();
    }
}
