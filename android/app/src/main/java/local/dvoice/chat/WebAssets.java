package local.dvoice.chat;

import android.content.Context;
import android.webkit.WebResourceResponse;

import androidx.webkit.WebViewAssetLoader;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Отдаёт страницу приложения: сначала обновлённую (из files/web), иначе встроенную из assets. */
final class WebAssets implements WebViewAssetLoader.PathHandler {
    private final Context ctx;

    WebAssets(Context c) {
        ctx = c.getApplicationContext();
    }

    @Override
    public WebResourceResponse handle(String path) {
        if (path == null || path.length() == 0) path = "index.html";
        if (!path.equals("index.html") && !path.equals("peerjs.min.js")) return notFound();
        try {
            File f = WebUpdater.updatedFile(ctx, path);
            InputStream in = (f != null) ? new FileInputStream(f) : ctx.getAssets().open(path);
            String mime = path.endsWith(".html") ? "text/html" : "text/javascript";
            return new WebResourceResponse(mime, "utf-8", in);
        } catch (IOException e) {
            return notFound();
        }
    }

    private static WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", null,
                new ByteArrayInputStream(new byte[0]));
    }
}
