package local.dvoice.chat;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

import java.util.ArrayList;

public class MainActivity extends Activity {
    static final String HOST = "appassets.androidplatform.net";
    static final String START_URL = "https://" + HOST + "/app/index.html";
    private static final int REQ_PERMS = 1;
    private static final long UPDATE_EVERY_MS = 20L * 60L * 1000L;

    private WebView web;
    private Bridge bridge;
    private PermissionRequest pendingWebPerm;
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
        });

        bridge = new Bridge(this, web);
        web.addJavascriptInterface(bridge, "DVAndroid");
        OverlayController.configure(this, bridge.overlayEnabled(), bridge.overlayCorner());

        askStartupPermissions();
        web.loadUrl(START_URL);
        handler.postDelayed(updateTick, 4000);
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

    private void handleWebPermission(PermissionRequest request) {
        if (!HOST.equals(request.getOrigin().getHost())) {
            request.deny();
            return;
        }
        boolean wantsAudio = false;
        for (String r : request.getResources()) {
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) wantsAudio = true;
        }
        if (!wantsAudio) {
            request.deny();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
        } else {
            if (pendingWebPerm != null) pendingWebPerm.deny();
            pendingWebPerm = request;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_PERMS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (pendingWebPerm != null) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                pendingWebPerm.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            } else {
                pendingWebPerm.deny();
            }
            pendingWebPerm = null;
        }
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
