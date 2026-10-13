package local.dvoice.chat;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Мост между страницей (index.html) и Android. Доступен в JS как window.DVAndroid. */
public class Bridge {
    private final Activity act;
    private final WebView web;

    Bridge(Activity act, WebView web) {
        this.act = act;
        this.web = web;
    }

    private SharedPreferences prefs() {
        return act.getSharedPreferences("dvoice", Context.MODE_PRIVATE);
    }

    boolean overlayEnabled() {
        return prefs().getBoolean("overlay_enabled", false);
    }

    String overlayCorner() {
        String c = prefs().getString("overlay_corner", "tr");
        return c == null ? "tr" : c;
    }

    void shutdown() {
        act.stopService(new Intent(act, CallService.class));
        OverlayController.update(act, new ArrayList<OverlayController.Row>());
    }

    @JavascriptInterface
    public String info() {
        JSONObject o = new JSONObject();
        try {
            String app = "?";
            try {
                app = act.getPackageManager().getPackageInfo(act.getPackageName(), 0).versionName;
            } catch (Exception ignored) {
            }
            o.put("app", app);
            o.put("web", WebUpdater.current(act));
        } catch (Exception ignored) {
        }
        return o.toString();
    }

    // ---------- звонок: служба переднего плана держит микрофон в фоне ----------

    @JavascriptInterface
    public void startCall(final String room) {
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    Intent i = new Intent(act, CallService.class);
                    i.putExtra("room", room);
                    act.startForegroundService(i);
                } catch (Exception ignored) {
                }
            }
        });
    }

    @JavascriptInterface
    public void stopCall() {
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                act.stopService(new Intent(act, CallService.class));
                OverlayController.update(act, new ArrayList<OverlayController.Row>());
            }
        });
    }

    // ---------- оверлей ----------

    @JavascriptInterface
    public String getOverlay() {
        JSONObject o = new JSONObject();
        try {
            o.put("enabled", overlayEnabled());
            o.put("corner", overlayCorner());
            o.put("permission", Settings.canDrawOverlays(act));
        } catch (Exception ignored) {
        }
        return o.toString();
    }

    @JavascriptInterface
    public void setOverlay(final boolean enabled, final String corner) {
        final String c = ("tl".equals(corner) || "tr".equals(corner) || "bl".equals(corner) || "br".equals(corner)) ? corner : "tr";
        prefs().edit().putBoolean("overlay_enabled", enabled).putString("overlay_corner", c).apply();
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                OverlayController.configure(act, enabled, c);
            }
        });
    }

    @JavascriptInterface
    public void overlayUpdate(String json) {
        final List<OverlayController.Row> rows = new ArrayList<OverlayController.Row>();
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length() && i < 8; i++) {
                JSONObject o = a.getJSONObject(i);
                OverlayController.Row r = new OverlayController.Row();
                String n = o.optString("name", "");
                r.name = n.length() > 40 ? n.substring(0, 40) : n;
                r.speaking = o.optBoolean("speaking", false);
                r.muted = o.optBoolean("muted", false);
                rows.add(r);
            }
        } catch (Exception ignored) {
        }
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                OverlayController.update(act, rows);
            }
        });
    }

    @JavascriptInterface
    public void requestOverlayPermission() {
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + act.getPackageName()));
                    act.startActivity(i);
                } catch (Exception ignored) {
                }
            }
        });
    }

    // ---------- избранные каналы: слежение и уведомления ----------

    @JavascriptInterface
    public void setWatch(final String json) {
        try {
            new JSONObject(json);
        } catch (Exception e) {
            return;
        }
        prefs().edit().putString("watch_cfg", json).apply();
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                WatchService.sync(act);
            }
        });
    }

    @JavascriptInterface
    public void setInRoom(String room) {
        WatchService.inRoom = room == null ? "" : room;
    }

    // ---------- батарея ----------

    @JavascriptInterface
    public boolean batteryIgnored() {
        try {
            PowerManager pm = (PowerManager) act.getSystemService(Context.POWER_SERVICE);
            return pm.isIgnoringBatteryOptimizations(act.getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    @JavascriptInterface
    public void ignoreBattery() {
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:" + act.getPackageName()));
                    act.startActivity(i);
                } catch (Exception e) {
                    try {
                        act.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    } catch (Exception ignored) {
                    }
                }
            }
        });
    }

    // ---------- обновление страницы ----------

    @JavascriptInterface
    public void checkUpdate() {
        WebUpdater.check(act, new WebUpdater.Callback() {
            @Override
            public void done(final String json) {
                web.post(new Runnable() {
                    @Override
                    public void run() {
                        web.evaluateJavascript("window.dvUpdate && window.dvUpdate(" + json + ")", null);
                    }
                });
            }
        });
    }
}
