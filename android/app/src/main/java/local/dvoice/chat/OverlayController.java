package local.dvoice.chat;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Оверлей «кто говорит» поверх других приложений. Все методы вызываются из главного потока. */
final class OverlayController {
    static final class Row {
        String name = "";
        boolean speaking;
        boolean muted;
    }

    private static Context app;
    private static WindowManager wm;
    private static LinearLayout view;
    private static String viewCorner = "";
    private static boolean enabled = false;
    private static String corner = "tr";
    private static boolean appVisible = true;
    private static List<Row> rows = new ArrayList<Row>();

    private OverlayController() {
    }

    static void configure(Context ctx, boolean en, String c) {
        app = ctx.getApplicationContext();
        enabled = en;
        corner = c == null ? "tr" : c;
        refresh();
    }

    static void update(Context ctx, List<Row> r) {
        app = ctx.getApplicationContext();
        rows = r;
        refresh();
    }

    static void setAppVisible(boolean v) {
        appVisible = v;
        refresh();
    }

    private static void refresh() {
        if (app == null) return;
        boolean show = enabled && !appVisible && !rows.isEmpty() && Settings.canDrawOverlays(app);
        if (!show) {
            removeView();
            return;
        }
        if (view != null && !viewCorner.equals(corner)) removeView();
        if (view == null) addView();
        if (view != null) rebuild();
    }

    private static int dp(float v) {
        return (int) (v * app.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static void addView() {
        try {
            wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
            LinearLayout v = new LinearLayout(app);
            v.setOrientation(LinearLayout.VERTICAL);

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            boolean bottom = corner.charAt(0) == 'b';
            boolean right = corner.charAt(1) == 'r';
            lp.gravity = (bottom ? Gravity.BOTTOM : Gravity.TOP) | (right ? Gravity.END : Gravity.START);
            lp.x = dp(8);
            lp.y = dp(bottom ? 48 : 36);
            v.setGravity(right ? Gravity.END : Gravity.START);

            wm.addView(v, lp);
            view = v;
            viewCorner = corner;
        } catch (Exception e) {
            view = null;
        }
    }

    private static void removeView() {
        if (view != null && wm != null) {
            try {
                wm.removeView(view);
            } catch (Exception ignored) {
            }
        }
        view = null;
        viewCorner = "";
    }

    private static void rebuild() {
        view.removeAllViews();
        for (Row r : rows) view.addView(makeRow(r));
    }

    private static LinearLayout makeRow(Row r) {
        LinearLayout row = new LinearLayout(app);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(4), dp(12), dp(4));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xB811151C);
        bg.setCornerRadius(dp(40));
        row.setBackground(bg);
        row.setAlpha(r.speaking ? 1f : 0.6f);

        TextView ava = new TextView(app);
        String n = r.name == null ? "" : r.name.trim();
        ava.setText(n.length() > 0 ? n.substring(0, 1).toUpperCase() : "?");
        ava.setTextColor(0xFFFFFFFF);
        ava.setTypeface(Typeface.DEFAULT_BOLD);
        ava.setTextSize(13);
        ava.setGravity(Gravity.CENTER);
        GradientDrawable ab = new GradientDrawable();
        ab.setShape(GradientDrawable.OVAL);
        ab.setColor(0xFF1A212B);
        ab.setStroke(dp(3), r.speaking ? 0xFFF5B942 : 0xFF2B3442);
        ava.setBackground(ab);
        row.addView(ava, new LinearLayout.LayoutParams(dp(30), dp(30)));

        TextView nm = new TextView(app);
        nm.setText(r.name);
        nm.setTextColor(0xFFFFFFFF);
        nm.setTypeface(Typeface.DEFAULT_BOLD);
        nm.setTextSize(14);
        nm.setSingleLine(true);
        nm.setEllipsize(TextUtils.TruncateAt.END);
        nm.setMaxWidth(dp(180));
        if (r.muted) nm.setPaintFlags(nm.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.leftMargin = dp(8);
        row.addView(nm, nlp);

        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(3);
        rlp.bottomMargin = dp(3);
        row.setLayoutParams(rlp);
        return row;
    }
}
