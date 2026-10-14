package local.dvoice.chat;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.util.Random;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Следит за входом друзей в одну комнату, не используя WebRTC: держит соединение с сигнальным сервером PeerJS
 * под ID dgn-watch-v1-&lt;код&gt;-N и читает входящие OFFER с метаданными {dv:"join", name}.
 * Все методы вызываются из главного потока.
 */
final class RoomWatcher {
    interface Listener {
        void onJoin(String room, String label, String name);
    }

    private static final String PREFIX = "dgn-watch-v1-";
    private static final int MAX_SLOTS = 6;

    private final OkHttpClient client;
    private final String room;
    private final Listener listener;
    private final String host;
    private final int port;
    private final String path;
    private final boolean secure;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();

    String label;
    private WebSocket ws;
    private int gen = 0;
    private int slot = 1;
    private long backoff = 3000;
    private boolean stopped = true;
    private boolean taken = false;

    private final Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            WebSocket w = ws;
            if (w != null && !stopped) {
                w.send("{\"type\":\"HEARTBEAT\"}");
                h.postDelayed(this, 5000);
            }
        }
    };

    private final Runnable reconnect = new Runnable() {
        @Override
        public void run() {
            connect();
        }
    };

    RoomWatcher(OkHttpClient client, String host, int port, String path, boolean secure,
                String room, String label, Listener listener) {
        this.client = client;
        this.host = host;
        this.port = port;
        this.path = path;
        this.secure = secure;
        this.room = room;
        this.label = label;
        this.listener = listener;
    }

    void start() {
        stopped = false;
        slot = 1;
        backoff = 3000;
        connect();
    }

    void stop() {
        stopped = true;
        gen++;
        h.removeCallbacksAndMessages(null);
        WebSocket w = ws;
        ws = null;
        if (w != null) {
            try {
                w.close(1000, "bye");
            } catch (Exception ignored) {
            }
        }
    }

    private String token() {
        return Long.toString(Math.abs(rnd.nextLong()), 36);
    }

    private void connect() {
        if (stopped) return;
        final int g = ++gen;
        taken = false;
        String id = PREFIX + room + "-" + slot;
        String url = (secure ? "wss://" : "ws://") + host + ":" + port + path + "peerjs?key=peerjs&id=" + id + "&token=" + token();
        Request req = new Request.Builder().url(url).build();
        ws = client.newWebSocket(req, new WebSocketListener() {
            @Override
            public void onMessage(final WebSocket w, final String text) {
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        handle(g, w, text);
                    }
                });
            }

            @Override
            public void onClosed(WebSocket w, int code, String reason) {
                postClosed(g);
            }

            @Override
            public void onFailure(WebSocket w, Throwable t, Response r) {
                postClosed(g);
            }
        });
    }

    private void postClosed(final int g) {
        h.post(new Runnable() {
            @Override
            public void run() {
                closed(g);
            }
        });
    }

    private void handle(int g, WebSocket w, String text) {
        if (g != gen || stopped) return;
        try {
            JSONObject m = new JSONObject(text);
            String type = m.optString("type", "");
            if ("OPEN".equals(type)) {
                backoff = 3000;
                h.removeCallbacks(heartbeat);
                h.postDelayed(heartbeat, 5000);
            } else if ("ID-TAKEN".equals(type)) {
                taken = true;
                w.close(1000, "taken");
            } else if ("OFFER".equals(type)) {
                JSONObject payload = m.optJSONObject("payload");
                JSONObject md = payload != null ? payload.optJSONObject("metadata") : null;
                if (md != null && "join".equals(md.optString("dv", ""))) {
                    String name = md.optString("name", "");
                    if (name.length() > 40) name = name.substring(0, 40);
                    listener.onJoin(room, label, name);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void closed(int g) {
        if (g != gen) return;
        h.removeCallbacks(heartbeat);
        ws = null;
        if (stopped) return;
        h.removeCallbacks(reconnect);
        if (taken) {
            slot++;
            if (slot > MAX_SLOTS) {
                slot = 1;
                h.postDelayed(reconnect, 30000);
            } else {
                h.postDelayed(reconnect, 200);
            }
        } else {
            long d = backoff;
            backoff = Math.min(backoff * 2, 60000);
            h.postDelayed(reconnect, d);
        }
    }
}
