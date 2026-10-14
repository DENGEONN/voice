package local.dvoice.chat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

/**
 * Служба переднего плана: пока она жива (даже если приложение закрыто, смахнуто из недавних),
 * следит за избранными каналами и показывает уведомление, когда кто-то заходит.
 */
public class WatchService extends Service implements RoomWatcher.Listener {
    private static final String CH_FG = "dvoice_watch";
    private static final String CH_JOIN = "dvoice_join";
    private static final int FG_ID = 2;
    private static final long THROTTLE_MS = 90L * 1000L;

    /** Комната, в которой пользователь сейчас сидит (о ней уведомления не нужны). */
    static volatile String inRoom = "";

    private final Map<String, RoomWatcher> watchers = new HashMap<String, RoomWatcher>();
    private final Map<String, Long> seen = new HashMap<String, Long>();
    private OkHttpClient client;
    private String selfName = "";
    private String serverKey = "";
    private String srvHost = "0.peerjs.com";
    private int srvPort = 443;
    private String srvPath = "/";
    private boolean srvSecure = true;

    /** Запускает или останавливает службу в зависимости от сохранённых настроек. */
    static void sync(Context ctx) {
        Intent i = new Intent(ctx, WatchService.class);
        try {
            if (hasWork(ctx)) ctx.startForegroundService(i);
            else ctx.stopService(i);
        } catch (Exception ignored) {
        }
    }

    private static JSONObject readCfg(Context ctx) {
        try {
            SharedPreferences p = ctx.getSharedPreferences("dvoice", Context.MODE_PRIVATE);
            String s = p.getString("watch_cfg", null);
            return s == null ? null : new JSONObject(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static String sanitizeRoom(String v) {
        if (v == null) return "";
        return v.trim().toLowerCase().replaceAll("[^a-z0-9_-]", "");
    }

    private static boolean hasWork(Context ctx) {
        JSONObject c = readCfg(ctx);
        if (c == null || !c.optBoolean("enabled", true)) return false;
        JSONArray favs = c.optJSONArray("favs");
        if (favs == null) return false;
        for (int i = 0; i < favs.length(); i++) {
            JSONObject f = favs.optJSONObject(i);
            if (f != null && f.optBoolean("notify", true) && sanitizeRoom(f.optString("room", "")).length() >= 4) return true;
        }
        return false;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        client = new OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CH_FG, "Слежение за каналами", NotificationManager.IMPORTANCE_LOW));
        NotificationChannel join = new NotificationChannel(CH_JOIN, "Вход в каналы", NotificationManager.IMPORTANCE_HIGH);
        nm.createNotificationChannel(join);

        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CH_FG)
                .setContentTitle("DVoice")
                .setContentText("Слежу за избранными каналами")
                .setSmallIcon(R.drawable.ic_stat)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(FG_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(FG_ID, n);
            }
        } catch (Exception e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        applyConfig();
        return START_STICKY;
    }

    private void applyConfig() {
        JSONObject c = readCfg(this);
        Set<String> want = new HashSet<String>();
        Map<String, String> labels = new HashMap<String, String>();
        boolean enabled = c != null && c.optBoolean("enabled", true);
        selfName = c != null ? c.optString("name", "").trim() : "";

        // адрес сервера знакомства (по умолчанию общий PeerJS); при смене перезапускаем наблюдателей
        String host = "0.peerjs.com";
        int port = 443;
        String path = "/";
        boolean secure = true;
        JSONObject sv = c != null ? c.optJSONObject("server") : null;
        if (sv != null && sv.optString("host", "").length() > 0) {
            host = sv.optString("host", host);
            secure = sv.optBoolean("secure", true);
            port = sv.optInt("port", secure ? 443 : 80);
            path = sv.optString("path", "/");
            if (!path.startsWith("/")) path = "/" + path;
            if (!path.endsWith("/")) path = path + "/";
        }
        String key = host + "|" + port + "|" + path + "|" + secure;
        if (!key.equals(serverKey)) {
            for (RoomWatcher w : watchers.values()) w.stop();
            watchers.clear();
            serverKey = key;
            srvHost = host;
            srvPort = port;
            srvPath = path;
            srvSecure = secure;
        }
        if (enabled) {
            JSONArray favs = c.optJSONArray("favs");
            for (int i = 0; favs != null && i < favs.length(); i++) {
                JSONObject f = favs.optJSONObject(i);
                if (f == null || !f.optBoolean("notify", true)) continue;
                String room = sanitizeRoom(f.optString("room", ""));
                if (room.length() < 4) continue;
                String label = f.optString("label", "").trim();
                want.add(room);
                labels.put(room, label.length() > 0 ? label : room);
            }
        }
        Iterator<Map.Entry<String, RoomWatcher>> it = watchers.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, RoomWatcher> e = it.next();
            if (!want.contains(e.getKey())) {
                e.getValue().stop();
                it.remove();
            }
        }
        for (String room : want) {
            RoomWatcher w = watchers.get(room);
            if (w != null) {
                w.label = labels.get(room);
            } else {
                w = new RoomWatcher(client, srvHost, srvPort, srvPath, srvSecure, room, labels.get(room), this);
                watchers.put(room, w);
                w.start();
            }
        }
        if (watchers.isEmpty()) stopSelf();
    }

    @Override
    public void onJoin(String room, String label, String name) {
        if (room.equals(inRoom)) return;
        String who = name.length() > 0 ? name : "Кто-то";
        if (selfName.length() > 0 && selfName.equals(name)) return;
        String key = room + "|" + who;
        long now = System.currentTimeMillis();
        Long last = seen.get(key);
        if (last != null && now - last < THROTTLE_MS) return;
        seen.put(key, now);

        Intent open = new Intent(this, MainActivity.class);
        open.putExtra("room", room);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, room.hashCode(), open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CH_JOIN)
                .setContentTitle("DVoice")
                .setContentText("В канал «" + label + "» присоединился " + who)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .build();
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(key.hashCode(), n);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onDestroy() {
        for (RoomWatcher w : watchers.values()) w.stop();
        watchers.clear();
        super.onDestroy();
    }
}
