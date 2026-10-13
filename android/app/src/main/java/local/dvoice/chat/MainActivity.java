package local.dvoice.chat;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;

import java.util.ArrayList;

public class MainActivity extends Activity {
    static final String HOST = "appassets.androidplatform.net";
    static final String START_URL = "https://" + HOST + "/app/index.html";
    private static final int REQ_PERMS = 1;
    private static final long UPDATE_EVERY_MS = 20L * 60L * 1000L;

    private WebView web;
    private Bridge bridge;
    private PermissionRequest pendingWebPerm;
    private View customView;
    private String pendingRoom;
    private boolean pageLoaded = false;
    private WebChromeClient.CustomViewCallback customCallback;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable updateTick = new Runnable() {
        @Override
        public void run() {
            if (bridge != null) bridge.checkUpdate();
            handler.postDelayed(this, UPDATE_EVERY_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.parseColor("#11151C"));
        getWindow().setNavigationBarColor(Color.parseColor("#11151C"));

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#11151C"));
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        WebView.setWebContentsDebuggingEnabled(true);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/app/", new WebAssets(this))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageLoaded = true;
                dispatchRoom();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // только наши страницы; всё внешнее блокируем
                return !HOST.equals(request.getUrl().getHost());
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        handleWebPermission(request);
                    }
                });
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                showCustomView(view, callback);
            }

            @Override
            public void onHideCustomView() {
                hideCustomView();
            }
        });

        bridge = new Bridge(this, web);
        web.addJavascriptInterface(bridge, "DVAndroid");
        OverlayController.configure(this, bridge.overlayEnabled(), bridge.overlayCorner());

        pendingRoom = getIntent() != null ? getIntent().getStringExtra("room") : null;
        askStartupPermissions();
        WatchService.sync(this);
        web.loadUrl(START_URL);
        handler.postDelayed(updateTick, 4000);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String r = intent != null ? intent.getStringExtra("room") : null;
        if (r != null && r.length() > 0) {
            pendingRoom = r;
            if (pageLoaded) dispatchRoom();
        }
    }

    /** Нажали на уведомление «X присоединился»: просим страницу открыть эту комнату. */
    private void dispatchRoom() {
        if (pendingRoom == null || web == null) return;
        String r = pendingRoom;
        pendingRoom = null;
        web.evaluateJavascript("window.dvOpenRoom && window.dvOpenRoom(" + JSONObject.quote(r) + ")", null);
    }

    private void askStartupPermissions() {
        ArrayList<String> need = new ArrayList<String>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), REQ_PERMS);
    }

    private boolean has(String perm) {
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    private void handleWebPermission(PermissionRequest request) {
        if (!HOST.equals(request.getOrigin().getHost())) {
            request.deny();
            return;
        }
        boolean wantsAudio = false;
        boolean wantsVideo = false;
        for (String r : request.getResources()) {
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) wantsAudio = true;
            else if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) wantsVideo = true;
        }
        if (!wantsAudio && !wantsVideo) {
            request.deny();
            return;
        }
        ArrayList<String> need = new ArrayList<String>();
        if (wantsAudio && !has(Manifest.permission.RECORD_AUDIO)) need.add(Manifest.permission.RECORD_AUDIO);
        if (wantsVideo && !has(Manifest.permission.CAMERA)) need.add(Manifest.permission.CAMERA);
        if (need.isEmpty()) {
            grantWeb(request);
            return;
        }
        if (pendingWebPerm != null) pendingWebPerm.deny();
        pendingWebPerm = request;
        requestPermissions(need.toArray(new String[0]), REQ_PERMS);
    }

    /** Отдаём странице только то, на что у приложения есть разрешение Android. */
    private void grantWeb(PermissionRequest request) {
        ArrayList<String> ok = new ArrayList<String>();
        for (String r : request.getResources()) {
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r) && has(Manifest.permission.RECORD_AUDIO)) ok.add(r);
            else if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r) && has(Manifest.permission.CAMERA)) ok.add(r);
        }
        if (ok.isEmpty()) request.deny();
        else request.grant(ok.toArray(new String[0]));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (pendingWebPerm != null) {
            PermissionRequest req = pendingWebPerm;
            pendingWebPerm = null;
            grantWeb(req);
        }
    }

    // ---------- видео на весь экран ----------

    private void showCustomView(View view, WebChromeClient.CustomViewCallback callback) {
        if (customView != null) {
            callback.onCustomViewHidden();
            return;
        }
        customView = view;
        customCallback = callback;
        ((FrameLayout) getWindow().getDecorView()).addView(view,
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void hideCustomView() {
        if (customView == null) return;
        ((FrameLayout) getWindow().getDecorView()).removeView(customView);
        customView = null;
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        if (customCallback != null) customCallback.onCustomViewHidden();
        customCallback = null;
    }

    @Override
    protected void onStart() {
        super.onStart();
        OverlayController.setAppVisible(true);
    }

    @Override
    protected void onStop() {
        super.onStop();
        OverlayController.setAppVisible(false);
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            hideCustomView();
            return;
        }
        // не закрываем приложение: голос должен продолжать работать в фоне
        moveTaskToBack(true);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(updateTick);
        if (isFinishing()) {
            if (bridge != null) bridge.shutdown();
            if (web != null) {
                web.removeJavascriptInterface("DVAndroid");
                web.destroy();
            }
        }
        super.onDestroy();
    }
}
