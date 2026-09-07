package kr.ieum.app;

import android.content.SharedPreferences;
import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        registerPlugin(IeumNativePlugin.class);
        super.onCreate(savedInstanceState);
    }

    /**
     * OTA 웹 번들 부팅 가드.
     * Capacitor는 CapWebViewSettings.serverBasePath 에 저장된 경로(다운로드한 웹 번들)를 자동 적용한다.
     * 그 번들이 부팅에 실패하면(JS가 IeumNative.webReady()를 못 부름) 두 번째 시작에서 경로를 지워
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
