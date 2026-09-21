package kr.ieum.app;

import androidx.annotation.NonNull;
import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 이음 Play 결제 플러그인 (Play Billing Library 8.x, 1회성 비소모 상품 '이음 패스')
 *  - getProduct({sku})  : 상품 조회 → {available, price, priceMicros, currency, title}
 *  - purchase({sku})    : 결제창 → {status: purchased|pending|canceled|error, token, orderId, acknowledged, code, message}
 *  - restore({sku})     : 이 Google 계정의 구매 조회 → {ok, owned, pending, token, orderId}
 *  구매 확인(acknowledge)은 기기에서 처리한다(3일 내 미확인 시 자동 환불되는 것을 방지) — 별도 서버 불필요.
 *  결제창 밖에서 상태가 바뀐 구매(대기 결제 완료 등)는 'purchase' 이벤트로 알린다.
 */
@CapacitorPlugin(name = "IeumBilling")
public class IeumBillingPlugin extends Plugin implements PurchasesUpdatedListener {

    private interface Ready { void run(BillingResult error); }          // error == null → 연결됨
    private interface Owned { void run(boolean ok, Purchase purchase); } // ok=false → 조회 실패

    private BillingClient client;
    private final Map<String, ProductDetails> details = new HashMap<>();
    private final List<Ready> waiters = new ArrayList<>();
    private boolean connecting = false;
    private PluginCall purchaseCall = null;
    private String purchaseSku = null;

    @Override
    public void load() {
        client = BillingClient.newBuilder(getContext())
            .setListener(this)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .enableAutoServiceReconnection()
            .build();
    }

    // ---------- 연결 ----------
    private void connect(final Ready cb) {
        synchronized (waiters) {
            if (client.isReady()) { cb.run(null); return; }
            waiters.add(cb);
            if (connecting) return;
            connecting = true;
        }
        try {
            client.startConnection(new BillingClientStateListener() {
                @Override
                public void onBillingSetupFinished(@NonNull BillingResult r) {
                    flush(r.getResponseCode() == BillingClient.BillingResponseCode.OK ? null : r);
                }
                @Override
                public void onBillingServiceDisconnected() { /* enableAutoServiceReconnection()이 다음 호출 때 재연결 */ }
            });
        } catch (Exception e) {
            flush(BillingResult.newBuilder()
                .setResponseCode(BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE)
                .setDebugMessage(String.valueOf(e.getMessage())).build());
        }
    }

    private void flush(BillingResult error) {
        List<Ready> run;
        synchronized (waiters) { connecting = false; run = new ArrayList<>(waiters); waiters.clear(); }
        for (Ready r : run) { try { r.run(error); } catch (Exception ignored) {} }
    }

    // ---------- 상품 ----------
    private void queryDetails(final String sku, final Ready cb) {
        QueryProductDetailsParams params = QueryProductDetailsParams.newBuilder()
            .setProductList(Collections.singletonList(
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(sku).setProductType(BillingClient.ProductType.INAPP).build()))
            .build();
        client.queryProductDetailsAsync(params, (billingResult, result) -> {
            if (billingResult.getResponseCode() != BillingClient.BillingResponseCode.OK) { cb.run(billingResult); return; }
            for (ProductDetails pd : result.getProductDetailsList()) details.put(pd.getProductId(), pd);
            cb.run(null);
        });
    }

    private static ProductDetails.OneTimePurchaseOfferDetails offerOf(ProductDetails pd) {
        try {
            List<ProductDetails.OneTimePurchaseOfferDetails> list = pd.getOneTimePurchaseOfferDetailsList();
            if (list != null && !list.isEmpty()) return list.get(0);
        } catch (Throwable ignored) {}
        return pd.getOneTimePurchaseOfferDetails();
    }

    @PluginMethod
    public void getProduct(final PluginCall call) {
        final String sku = call.getString("sku", "");
        connect(err -> {
            if (err != null) { call.resolve(unavailable(err)); return; }
            queryDetails(sku, qerr -> {
                ProductDetails pd = details.get(sku);
                if (qerr != null || pd == null) { call.resolve(unavailable(qerr)); return; }
                JSObject o = new JSObject();
                o.put("available", true);
                o.put("sku", sku);
                o.put("title", pd.getName());
                ProductDetails.OneTimePurchaseOfferDetails offer = offerOf(pd);
                if (offer != null) {
                    o.put("price", offer.getFormattedPrice());
                    o.put("priceMicros", offer.getPriceAmountMicros());
                    o.put("currency", offer.getPriceCurrencyCode());
                }
                call.resolve(o);
            });
        });
    }

    private static JSObject unavailable(BillingResult err) {
        JSObject o = new JSObject();
        o.put("available", false);
        if (err != null) { o.put("code", err.getResponseCode()); o.put("message", err.getDebugMessage()); }
        return o;
    }

    // ---------- 결제 ----------
    @PluginMethod
    public void purchase(final PluginCall call) {
        final String sku = call.getString("sku", "");
        connect(err -> {
            if (err != null) { call.resolve(status("error", err)); return; }
            Ready launch = qerr -> {
                final ProductDetails pd = details.get(sku);
                if (qerr != null || pd == null) { call.resolve(status("error", qerr)); return; }
                BillingFlowParams.ProductDetailsParams.Builder b =
                    BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(pd);
                ProductDetails.OneTimePurchaseOfferDetails offer = offerOf(pd);
                String offerToken = null;
                try { if (offer != null) offerToken = offer.getOfferToken(); } catch (Throwable ignored) {}
                if (offerToken != null && !offerToken.isEmpty()) b.setOfferToken(offerToken);
                final BillingFlowParams flow = BillingFlowParams.newBuilder()
                    .setProductDetailsParamsList(Collections.singletonList(b.build())).build();
                getActivity().runOnUiThread(() -> {
                    purchaseCall = call; purchaseSku = sku;
                    BillingResult r = client.launchBillingFlow(getActivity(), flow);
                    int code = r.getResponseCode();
                    if (code == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) { finishOwned(sku); }
                    else if (code != BillingClient.BillingResponseCode.OK) { resolvePurchase(status("error", r)); }
                });
            };
            if (details.get(sku) != null) launch.run(null); else queryDetails(sku, launch);
        });
    }

    @Override
    public void onPurchasesUpdated(@NonNull BillingResult r, List<Purchase> purchases) {
        int code = r.getResponseCode();
        if (code == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (Purchase p : purchases) handle(p);
        } else if (code == BillingClient.BillingResponseCode.USER_CANCELED) {
            resolvePurchase(status("canceled", r));
        } else if (code == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED && purchaseSku != null) {
            finishOwned(purchaseSku);
        } else {
            resolvePurchase(status("error", r));
        }
    }

    /** 구매 1건 처리: PURCHASED면 acknowledge 후 알림, PENDING이면 대기 알림 */
    private void handle(final Purchase p) {
        if (p.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
            acknowledge(p, acked -> deliver(purchaseObj("purchased", p, acked)));
        } else if (p.getPurchaseState() == Purchase.PurchaseState.PENDING) {
            deliver(purchaseObj("pending", p, false));
        }
    }

    private interface Acked { void run(boolean acknowledged); }
    private void acknowledge(final Purchase p, final Acked cb) {
        if (p.isAcknowledged()) { cb.run(true); return; }
        client.acknowledgePurchase(
            AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.getPurchaseToken()).build(),
            ar -> cb.run(ar.getResponseCode() == BillingClient.BillingResponseCode.OK));
    }

    private void finishOwned(final String sku) {
        owned(sku, (ok, p) -> {
            if (ok && p != null && p.getPurchaseState() == Purchase.PurchaseState.PURCHASED) acknowledge(p, acked -> deliver(purchaseObj("purchased", p, acked)));
            else if (ok && p != null) deliver(purchaseObj("pending", p, false));
            else resolvePurchase(status("error", null));
        });
    }

    /** 결제창에서 온 결과면 대기 중인 호출을 끝내고, 아니면(대기 결제 완료 등) 이벤트로 알린다 */
    private void deliver(JSObject o) {
        if (purchaseCall != null) resolvePurchase(o);
        else notifyListeners("purchase", o, true);
    }

    private synchronized void resolvePurchase(JSObject o) {
        PluginCall c = purchaseCall; purchaseCall = null; purchaseSku = null;
        if (c != null) c.resolve(o);
    }

    // ---------- 복원 ----------
    private void owned(final String sku, final Owned cb) {
        client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(),
            (r, list) -> {
                if (r.getResponseCode() != BillingClient.BillingResponseCode.OK) { cb.run(false, null); return; }
                Purchase found = null;
                if (list != null) for (Purchase p : list) {
                    if (!p.getProducts().contains(sku)) continue;
                    if (p.getPurchaseState() == Purchase.PurchaseState.PURCHASED) { found = p; break; }
                    if (found == null) found = p;
                }
                cb.run(true, found);
            });
    }

    @PluginMethod
    public void restore(final PluginCall call) {
        final String sku = call.getString("sku", "");
        connect(err -> {
            if (err != null) { JSObject o = new JSObject(); o.put("ok", false); o.put("owned", false); o.put("code", err.getResponseCode()); call.resolve(o); return; }
            owned(sku, (ok, p) -> {
                final JSObject o = new JSObject();
                o.put("ok", ok);
                boolean isOwned = ok && p != null && p.getPurchaseState() == Purchase.PurchaseState.PURCHASED;
                o.put("owned", isOwned);
                o.put("pending", ok && p != null && p.getPurchaseState() == Purchase.PurchaseState.PENDING);
                if (p != null) { o.put("token", p.getPurchaseToken()); o.put("orderId", p.getOrderId()); }
                if (isOwned) acknowledge(p, acked -> { o.put("acknowledged", acked); call.resolve(o); });
                else call.resolve(o);
            });
        });
    }

    // ---------- 결과 객체 ----------
    private static JSObject status(String s, BillingResult r) {
        JSObject o = new JSObject();
        o.put("status", s);
        if (r != null) { o.put("code", r.getResponseCode()); o.put("message", r.getDebugMessage()); }
        return o;
    }

    private static JSObject purchaseObj(String s, Purchase p, boolean acknowledged) {
        JSObject o = new JSObject();
        o.put("status", s);
        o.put("token", p.getPurchaseToken());
        o.put("orderId", p.getOrderId());
        o.put("acknowledged", acknowledged);
        return o;
    }
}
