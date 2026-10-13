package local.dvoice.chat;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Обновление страницы (index.html, peerjs.min.js) с GitHub Pages без переустановки APK.
 * Файлы проверяются по sha256 из version.json. Новая версия подхватывается при следующей загрузке страницы.
 */
final class WebUpdater {
    interface Callback {
        void done(String json);
    }

    static final String[] BASES = {
            "https://dengeonn.github.io/voice/dvoice",
            "https://raw.githubusercontent.com/DENGEONN/voice/main/dvoice"
    };
    private static final String[] FILES = {"index.html", "peerjs.min.js"};
    private static final int APP_LEVEL = 3;   // растёт, только если нужна новая APK
    private static final Pattern VER = Pattern.compile("^\\d+\\.\\d+\\.\\d+$");
    private static final Pattern HEX = Pattern.compile("^[a-fA-F0-9]{64}$");
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private WebUpdater() {
    }

    static int cmp(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        for (int i = 0; i < 3; i++) {
            int x = i < pa.length ? parse(pa[i]) : 0;
            int y = i < pb.length ? parse(pb[i]) : 0;
            if (x != y) return x > y ? 1 : -1;
        }
        return 0;
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static String readAsset(Context c, String name) {
        try {
            InputStream in = c.getAssets().open(name);
            try {
                return new String(readAll(in, 4096), "UTF-8").trim();
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return null;
        }
    }

    static String bundledVersion(Context c) {
        String v = readAsset(c, "version.txt");
        return (v != null && VER.matcher(v).matches()) ? v : "0.0.0";
    }

    private static String updatedVersion(Context c) {
        try {
            File f = new File(new File(c.getFilesDir(), "web"), "version.txt");
            if (!f.isFile()) return null;
            InputStream in = new java.io.FileInputStream(f);
            try {
                String v = new String(readAll(in, 4096), "UTF-8").trim();
                return VER.matcher(v).matches() ? v : null;
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return null;
        }
    }

    static String current(Context c) {
        String b = bundledVersion(c);
        String u = updatedVersion(c);
        return (u != null && cmp(u, b) > 0) ? u : b;
    }

    /** Файл обновлённой страницы, если он новее встроенной; иначе null. */
    static File updatedFile(Context c, String name) {
        String u = updatedVersion(c);
        if (u == null || cmp(u, bundledVersion(c)) <= 0) return null;
        File f = new File(new File(c.getFilesDir(), "web"), name);
        return f.isFile() ? f : null;
    }

    static void check(final Context ctx, final Callback cb) {
        if (!RUNNING.compareAndSet(false, true)) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                String res;
                try {
                    res = doCheck(ctx.getApplicationContext());
                } catch (Throwable t) {
                    res = result("error", null, String.valueOf(t.getMessage()));
                } finally {
                    RUNNING.set(false);
                }
                cb.done(res);
            }
        }).start();
    }

    private static String doCheck(Context ctx) {
        String current = current(ctx);
        JSONObject man = null;
        String base = null;
        String lastErr = "";
        for (String b : BASES) {
            try {
                String txt = new String(get(b + "/version.json?t=" + System.currentTimeMillis(), 262144), "UTF-8");
                JSONObject m = new JSONObject(txt);
                validate(m);
                man = m;
                base = b;
                break;
            } catch (Exception e) {
                lastErr = String.valueOf(e.getMessage());
            }
        }
        if (man == null) return result("error", null, lastErr);

        try {
            if (man.optInt("minAndroid", 0) > APP_LEVEL) {
                return result("needs-new-app", man.getString("version"), "Нужна новая версия приложения DVoice (скачай APK заново)");
            }
            String ver = man.getString("version");
            if (cmp(ver, current) <= 0) return result("uptodate", current, null);

            JSONObject files = man.getJSONObject("files");
            File tmp = new File(ctx.getFilesDir(), "web.tmp");
            deleteRec(tmp);
            if (!tmp.mkdirs()) throw new IOException("mkdir failed");
            for (String name : FILES) {
                byte[] data = get(base + "/" + name, 8 * 1024 * 1024);
                if (!sha256(data).equalsIgnoreCase(files.getString(name))) throw new IOException("hash mismatch: " + name);
                write(new File(tmp, name), data);
            }
            write(new File(tmp, "version.txt"), ver.getBytes("UTF-8"));
            File dst = new File(ctx.getFilesDir(), "web");
            deleteRec(dst);
            if (!tmp.renameTo(dst)) throw new IOException("rename failed");
            return result("staged", ver, null);
        } catch (Exception e) {
            return result("error", null, String.valueOf(e.getMessage()));
        }
    }

    private static void validate(JSONObject m) throws Exception {
        if (!VER.matcher(m.optString("version", "")).matches()) throw new IOException("bad version");
        JSONObject files = m.getJSONObject("files");
        for (String name : FILES) {
            if (!HEX.matcher(files.optString(name, "")).matches()) throw new IOException("bad hash for " + name);
        }
    }

    private static String result(String status, String version, String message) {
        JSONObject o = new JSONObject();
        try {
            o.put("status", status);
            o.put("version", version == null ? JSONObject.NULL : version);
            o.put("message", message == null ? JSONObject.NULL : message);
        } catch (Exception ignored) {
        }
        return o.toString();
    }

    private static byte[] get(String url, int limit) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setUseCaches(false);
        c.setRequestProperty("Cache-Control", "no-cache");
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            InputStream in = c.getInputStream();
            try {
                return readAll(in, limit);
            } finally {
                in.close();
            }
        } finally {
            c.disconnect();
        }
    }

    private static byte[] readAll(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > limit) throw new IOException("file too large");
        }
        return out.toByteArray();
    }

    private static String sha256(byte[] data) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static void write(File f, byte[] data) throws IOException {
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(data);
        } finally {
            out.close();
        }
    }

    private static void deleteRec(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRec(k);
        f.delete();
    }
}
