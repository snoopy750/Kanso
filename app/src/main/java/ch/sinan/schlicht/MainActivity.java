package ch.sinan.schlicht;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.util.Calendar;
import android.provider.AlarmClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.text.Collator;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {

    static class AppEntry {
        ComponentName cn;
        String key, label, norm;
        char letter;
    }

    private float dp;
    private int touchSlop;
    private SharedPreferences prefs;
    private LinearLayout home, drawer, favList;
    private BatteryClockView clock;
    private EditText search;
    private ListView list;
    private AlphabetBar alpha;
    private AppAdapter adapter;
    private GestureDetector gestures;
    private final List<AppEntry> apps = new ArrayList<>();
    private final List<AppEntry> shown = new ArrayList<>();

    // Wisch Startseite <-> alle Apps (0 = Startseite, 1 = alle Apps)
    private boolean drawerOpen;
    private float progress;
    private ValueAnimator progressAnim;
    private boolean dragCandidate, dragging;
    private float downX, downY, dragStart;
    private VelocityTracker velocity;

    // Favoriten verschieben
    private View favDrag;
    private AppEntry favEntry;
    private int favFrom, favCur;
    private float favStartRawY, lastDownRawY;
    private boolean favMoved;

    private boolean resumed, introPending;

    private final BroadcastReceiver battery = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            int lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            boolean ch = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL;
            if (lvl >= 0 && scale > 0) clock.setBattery(Math.round(lvl * 100f / scale), ch);
        }
    };
    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { clock.updateTime(); refreshBans(); }
    };
    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { loadApps(); }
    };
    private final BroadcastReceiver unlocked = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (resumed) clock.playIntro(); else introPending = true;
        }
    };

    private int px(float v) { return Math.round(v * dp); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        dp = getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        prefs = getSharedPreferences("schlicht", MODE_PRIVATE);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        home = buildHome();
        drawer = buildDrawer();
        root.addView(home, new FrameLayout.LayoutParams(-1, -1));
        root.addView(drawer, new FrameLayout.LayoutParams(-1, -1));
        drawer.setVisibility(View.GONE);
        setContentView(root);

        // Nur noch für "nach unten wischen = Benachrichtigungen"
        gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null || e2 == null || drawerOpen) return false;
                float dx = e2.getX() - e1.getX(), dy = e2.getY() - e1.getY();
                if (dy > px(70) && Math.abs(dy) > Math.abs(dx) * 1.5f) { expandNotifications(); return true; }
                return false;
            }
        });

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        register(packages, pf);
        register(unlocked, new IntentFilter(Intent.ACTION_USER_PRESENT));

        loadApps();
        introPending = true;
    }

    // ---------- Aufbau ----------

    private LinearLayout buildHome() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(px(20), px(32), px(16), px(12));

        clock = new BatteryClockView(this);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(px(165), px(165));
        clp.gravity = Gravity.CENTER_HORIZONTAL;
        clp.bottomMargin = px(24);
        v.addView(clock, clp);
        clock.setOnClickListener(x -> openClock());
        clock.setOnLongClickListener(x -> { start(new Intent(Intent.ACTION_POWER_USAGE_SUMMARY)); return true; });

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipChildren(false);
        favList = new LinearLayout(this);
        favList.setOrientation(LinearLayout.VERTICAL);
        favList.setClipChildren(false);
        sv.addView(favList);
        v.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(iconButton(R.drawable.ic_phone, () -> start(new Intent(Intent.ACTION_DIAL))));
        bar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        bar.addView(iconButton(R.drawable.ic_camera,
                () -> start(new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))));
        v.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        return v;
    }

    private ImageView iconButton(int res, Runnable action) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(res);
        iv.setPadding(px(10), px(10), px(10), px(10));
        iv.setLayoutParams(new LinearLayout.LayoutParams(px(52), px(52)));
        iv.setOnClickListener(x -> action.run());
        return iv;
    }

    private LinearLayout buildDrawer() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setBackgroundColor(Color.BLACK);
        v.setPadding(px(20), px(16), 0, 0);

        search = new EditText(this);
        search.setHint("Suchen");
        search.setHintTextColor(0xFF666666);
        search.setTextColor(Color.WHITE);
        search.setTextSize(20);
        search.setSingleLine(true);
        search.setBackground(null);
        search.setImeOptions(EditorInfo.IME_ACTION_GO);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setPadding(0, px(8), px(24), px(12));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { filter(s.toString()); }
        });
        search.setOnEditorActionListener((tv, id, ev) -> {
            if (!shown.isEmpty()) launch(shown.get(0));
            return true;
        });
        v.addView(search, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout f = new FrameLayout(this);
        f.setClipChildren(false);
        list = new ListView(this);
        list.setDivider(null);
        list.setVerticalScrollBarEnabled(false);
        list.setSelector(android.R.color.transparent);
        adapter = new AppAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, view, pos, id) -> launch(shown.get(pos)));
        list.setOnItemLongClickListener((p, view, pos, id) -> { showOptions(shown.get(pos)); return true; });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -1);
        lp.rightMargin = px(36);
        f.addView(list, lp);

        // Breiter als die Leiste, damit die Welle nach links ausschlagen kann
        alpha = new AlphabetBar(this);
        alpha.setPadding(0, px(4), 0, px(16));
        f.addView(alpha, new FrameLayout.LayoutParams(px(110), -1, Gravity.END));
        alpha.setListener(new AlphabetBar.Listener() {
            @Override public void onLetter(char c) { jumpTo(c); }
            @Override public void onRelease() {}
        });
        v.addView(f, new LinearLayout.LayoutParams(-1, 0, 1f));
        return v;
    }

    private TextView label(float sp) {
        TextView t = new TextView(this);
        t.setTextColor(Color.WHITE);
        t.setTextSize(sp);
        t.setPadding(0, px(11), 0, px(11));
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    class AppAdapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int p) { return shown.get(p); }
        @Override public long getItemId(int p) { return p; }
        @Override public View getView(int p, View cv, ViewGroup parent) {
            TextView t = cv instanceof TextView ? (TextView) cv : label(20);
            styleLabel(t, shown.get(p));
            return t;
        }
    }

    // ---------- Daten ----------

    private String displayName(AppEntry e) { return prefs.getString("name_" + e.key, e.label); }

    private static String normalize(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    private static char letterOf(String norm) {
        if (norm.isEmpty()) return '#';
        char c = Character.toUpperCase(norm.charAt(0));
        return (c >= 'A' && c <= 'Z') ? c : '#';
    }

    private void loadApps() {
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> ris = getPackageManager().queryIntentActivities(q, 0);
        apps.clear();
        for (ResolveInfo ri : ris) {
            if (getPackageName().equals(ri.activityInfo.packageName)) continue;
            AppEntry e = new AppEntry();
            e.cn = new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
            e.key = e.cn.flattenToString();
            e.label = String.valueOf(ri.loadLabel(getPackageManager())).trim();
            e.norm = normalize(displayName(e));
            e.letter = letterOf(e.norm);
            apps.add(e);
        }
        Collator col = Collator.getInstance();
        col.setStrength(Collator.PRIMARY);
        Collections.sort(apps, (a, b) -> col.compare(displayName(a), displayName(b)));
        filter(search.getText().toString());
        renderFavorites();
    }

    private void filter(String query) {
        String n = normalize(query.trim());
        shown.clear();
        if (n.isEmpty()) {
            shown.addAll(apps);
        } else {
            List<AppEntry> rest = new ArrayList<>();
            for (AppEntry e : apps) {
                if (e.norm.startsWith(n)) shown.add(e);
                else if (e.norm.contains(n)) rest.add(e);
            }
            shown.addAll(rest);
        }
        adapter.notifyDataSetChanged();
        alpha.setVisibility(n.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void jumpTo(char c) {
        int target = AlphabetBar.LETTERS.indexOf(c);
        for (int i = 0; i < shown.size(); i++) {
            if (AlphabetBar.LETTERS.indexOf(shown.get(i).letter) >= target) {
                list.setSelection(i);
                return;
            }
        }
        if (!shown.isEmpty()) list.setSelection(shown.size() - 1);
    }

    private List<String> favs() {
        List<String> l = new ArrayList<>();
        for (String k : prefs.getString("favs", "").split("\n")) if (!k.isEmpty()) l.add(k);
        return l;
    }

    private void saveFavs(List<String> l) {
        prefs.edit().putString("favs", TextUtils.join("\n", l)).apply();
        renderFavorites();
    }

    private void renderFavorites() {
        favList.removeAllViews();
        Map<String, AppEntry> byKey = new HashMap<>();
        for (AppEntry e : apps) byKey.put(e.key, e);
        for (String k : favs()) {
            AppEntry e = byKey.get(k);
            if (e == null) continue;
            TextView t = label(22);
            t.setPadding(0, px(14), 0, px(14));
            styleLabel(t, e);
            t.setTag(e.key);
            t.setPivotX(0);
            t.setOnClickListener(x -> launch(e));
            t.setOnLongClickListener(x -> { startFavDrag(t, e); return true; });
            t.setOnTouchListener(this::handleFavTouch);
            favList.addView(t, new LinearLayout.LayoutParams(-1, -2));
        }
        if (favList.getChildCount() == 0) {
            TextView hint = label(16);
            hint.setSingleLine(false);
            hint.setTextColor(0xFF777777);
            hint.setText("Nach links wischen für alle Apps.\nApp lange drücken → „Auf Startseite“.");
            favList.addView(hint);
        }
    }

    // ---------- Favoriten verschieben (lange drücken + ziehen) ----------

    private void startFavDrag(View v, AppEntry e) {
        favDrag = v;
        favEntry = e;
        favFrom = favCur = favList.indexOfChild(v);
        favStartRawY = lastDownRawY;
        favMoved = false;
        if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
        v.setTranslationZ(px(8));
        v.animate().scaleX(1.08f).scaleY(1.08f).setDuration(120).start();
    }

    private boolean handleFavTouch(View v, MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) lastDownRawY = ev.getRawY();
        if (favDrag != v) return false;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE: {
                float dy = ev.getRawY() - favStartRawY;
                if (Math.abs(dy) > touchSlop) favMoved = true;
                v.setTranslationY(dy);
                int h = Math.max(1, v.getHeight());
                int n = favList.getChildCount();
                int cur = Math.max(0, Math.min(n - 1, favFrom + Math.round(dy / h)));
                if (cur != favCur) {
                    favCur = cur;
                    v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    shiftOthers(h);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                endFavDrag(v, ev.getActionMasked() == MotionEvent.ACTION_UP);
                return true;
        }
        return true;
    }

    private void shiftOthers(int h) {
        for (int i = 0; i < favList.getChildCount(); i++) {
            if (i == favFrom) continue;
            float target = 0;
            if (favFrom < favCur && i > favFrom && i <= favCur) target = -h;
            else if (favCur < favFrom && i >= favCur && i < favFrom) target = h;
            favList.getChildAt(i).animate().translationY(target).setDuration(150).start();
        }
    }

    private void endFavDrag(View v, boolean released) {
        favDrag = null;
        if (!favMoved) {
            v.animate().scaleX(1f).scaleY(1f).translationY(0).setDuration(120)
                    .withEndAction(() -> v.setTranslationZ(0)).start();
            if (released) showOptions(favEntry);
            return;
        }
        final int from = favFrom, to = favCur;
        v.animate().translationY((to - from) * v.getHeight()).scaleX(1f).scaleY(1f).setDuration(150)
                .withEndAction(() -> {
                    List<String> keys = new ArrayList<>();
                    for (int i = 0; i < favList.getChildCount(); i++) {
                        Object tag = favList.getChildAt(i).getTag();
                        if (tag instanceof String) keys.add((String) tag);
                    }
                    if (from < keys.size() && to < keys.size()) keys.add(to, keys.remove(from));
                    saveFavs(keys);
                }).start();
    }

    // ---------- Aktionen ----------

    private void launch(AppEntry e) {
        if (isBanned(e.cn.getPackageName())) { showBanned(e); return; }
        launchNow(e);
    }

    private void launchNow(AppEntry e) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(e.cn)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(i);
        } catch (Exception ex) {
            Toast.makeText(this, "App lässt sich nicht öffnen", Toast.LENGTH_SHORT).show();
            loadApps();
        }
    }

    /** Uhr/Wecker öffnen – mit Ausweichlösungen, weil Hersteller-Uhren (z. B. Xiaomi) das Standard-Intent ablehnen. */
    private void openClock() {
        try {
            startActivity(new Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        } catch (Exception ignored) {
        }
        String[] known = {"com.android.deskclock", "com.google.android.deskclock",
                "com.sec.android.app.clockpackage", "com.oneplus.deskclock", "com.coloros.alarmclock"};
        for (String pkg : known) {
            Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i != null) { start(i); return; }
        }
        for (AppEntry e : apps) {
            String n = e.norm + " " + e.cn.getPackageName().toLowerCase(Locale.ROOT);
            if (n.contains("clock") || n.startsWith("uhr")) { launch(e); return; }
        }
        Toast.makeText(this, "Keine Uhr-App gefunden", Toast.LENGTH_SHORT).show();
    }

    private void start(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Nicht verfügbar", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------- Kanso-Panel (ersetzt die Android-Dialoge) ----------

    private FrameLayout root;
    private View sheetScrim;
    private LinearLayout sheetPanel;

    private List<TextView> showSheet(String title, String message, View extra, List<String> items, List<Runnable> acts) {
        closeSheet(false);
        View scrim = new View(this);
        scrim.setBackgroundColor(0xB3000000);
        scrim.setAlpha(0f);
        scrim.setOnClickListener(v -> closeSheet(true));
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setClickable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF080808);
        float r = px(28);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        bg.setStroke(Math.max(1, px(1)), 0xFF232323);
        panel.setBackground(bg);
        panel.setPadding(px(28), px(26), px(28), px(34));

        if (title != null) {
            TextView t = new TextView(this);
            t.setText(title.toUpperCase(Locale.getDefault()));
            t.setTextColor(0xFF6F6F6F);
            t.setTextSize(12);
            t.setLetterSpacing(0.16f);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setPadding(0, 0, 0, px(message != null ? 8 : 12));
            panel.addView(t);
        }
        if (message != null) {
            TextView m = new TextView(this);
            m.setText(message);
            m.setTextColor(0xFFBDBDBD);
            m.setTextSize(17);
            m.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            m.setPadding(0, 0, 0, px(14));
            panel.addView(m);
        }
        if (extra != null) panel.addView(extra);

        List<TextView> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            TextView it = new TextView(this);
            it.setText(items.get(i));
            it.setTextColor(Color.WHITE);
            it.setTextSize(20);
            it.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            it.setPadding(0, px(12), 0, px(12));
            final int k = i;
            it.setOnClickListener(v -> { closeSheet(true); acts.get(k).run(); });
            panel.addView(it, new LinearLayout.LayoutParams(-1, -2));
            out.add(it);
        }
        root.addView(panel, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        sheetScrim = scrim;
        sheetPanel = panel;
        panel.setVisibility(View.INVISIBLE);
        panel.post(() -> {
            panel.setTranslationY(panel.getHeight());
            panel.setVisibility(View.VISIBLE);
            panel.animate().translationY(0).setDuration(280).setInterpolator(new DecelerateInterpolator(2.2f)).start();
        });
        scrim.animate().alpha(1f).setDuration(240).start();
        return out;
    }

    private void closeSheet(boolean animate) {
        if (sheetPanel == null) return;
        View p = sheetPanel, sc = sheetScrim;
        sheetPanel = null;
        sheetScrim = null;
        hideKeyboard();
        if (!animate) { root.removeView(p); root.removeView(sc); return; }
        p.animate().translationY(p.getHeight()).setDuration(190).setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> root.removeView(p)).start();
        sc.animate().alpha(0f).setDuration(190).withEndAction(() -> root.removeView(sc)).start();
    }

    private void showOptions(AppEntry e) {
        List<String> f = favs();
        List<String> items = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        if (f.contains(e.key)) {
            items.add("Von Startseite entfernen");
            acts.add(() -> { f.remove(e.key); saveFavs(f); });
        } else {
            items.add("Auf Startseite");
            acts.add(() -> { f.add(e.key); saveFavs(f); });
        }
        String pkgName = e.cn.getPackageName();
        if (isBanned(pkgName)) {
            if (!isStrict(pkgName)) {
                items.add("Verbannung aufheben");
                acts.add(() -> { clearBan(pkgName); refreshBans(); });
            }
        } else {
            items.add("Verbannen");
            acts.add(() -> askBan(e));
        }
        items.add("Umbenennen");
        acts.add(() -> rename(e));
        Uri pkg = Uri.parse("package:" + pkgName);
        items.add("App-Info");
        acts.add(() -> start(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)));
        items.add("Deinstallieren");
        acts.add(() -> start(new Intent(Intent.ACTION_DELETE, pkg)));
        String msg = isBanned(pkgName) ? "Verbannt · noch " + remaining(pkgName) + (isStrict(pkgName) ? " · streng" : "") : null;
        showSheet(displayName(e), msg, null, items, acts);
    }

    // ---------- Verbannen ----------

    private final Handler ui = new Handler(Looper.getMainLooper());

    private long banUntil(String pkg) { return prefs.getLong("ban_" + pkg, 0); }

    private boolean isStrict(String pkg) { return prefs.getBoolean("banstrict_" + pkg, false); }

    private boolean isBanned(String pkg) {
        long u = banUntil(pkg);
        if (u == 0) return false;
        if (u <= System.currentTimeMillis()) { clearBan(pkg); return false; }
        return true;
    }

    private void clearBan(String pkg) {
        prefs.edit().remove("ban_" + pkg).remove("banstrict_" + pkg).apply();
    }

    private String remaining(String pkg) {
        long min = Math.max(1, (banUntil(pkg) - System.currentTimeMillis() + 59999) / 60000);
        if (min < 60) return min + " min";
        long h = min / 60, m = min % 60;
        return m == 0 ? h + " Std" : h + " Std " + m + " min";
    }

    private void styleLabel(TextView t, AppEntry e) {
        String pkg = e.cn.getPackageName();
        if (isBanned(pkg)) {
            t.setText(displayName(e) + "  · noch " + remaining(pkg));
            t.setTextColor(0xFF555555);
        } else {
            t.setText(displayName(e));
            t.setTextColor(Color.WHITE);
        }
    }

    private void refreshBans() {
        renderFavorites();
        adapter.notifyDataSetChanged();
    }

    private void askBan(AppEntry e) {
        List<String> items = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        long[] mins = {15, 60, 180};
        String[] names = {"15 Minuten", "1 Stunde", "3 Stunden"};
        for (int i = 0; i < mins.length; i++) {
            final long ms = mins[i] * 60_000L;
            items.add(names[i]);
            acts.add(() -> askMode(e, System.currentTimeMillis() + ms));
        }
        items.add("Bis morgen früh · 7:00");
        acts.add(() -> {
            Calendar c = Calendar.getInstance();
            c.set(Calendar.HOUR_OF_DAY, 7);
            c.set(Calendar.MINUTE, 0);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            if (c.getTimeInMillis() <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_YEAR, 1);
            askMode(e, c.getTimeInMillis());
        });
        showSheet(displayName(e) + " verbannen", "Wie lange?", null, items, acts);
    }

    private void askMode(AppEntry e, long until) {
        String pkg = e.cn.getPackageName();
        List<String> items = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        items.add("Sanft");
        acts.add(() -> { prefs.edit().putLong("ban_" + pkg, until).putBoolean("banstrict_" + pkg, false).apply(); refreshBans(); });
        items.add("Streng");
        acts.add(() -> { prefs.edit().putLong("ban_" + pkg, until).putBoolean("banstrict_" + pkg, true).apply(); refreshBans(); });
        showSheet(displayName(e) + " verbannen",
                "Sanft: nach 10 Sekunden Warten trotzdem öffnen.\nStreng: kein Notausgang.", null, items, acts);
    }

    private void showBanned(AppEntry e) {
        String pkg = e.cn.getPackageName();
        List<String> items = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        if (isStrict(pkg)) {
            items.add("Okay, ich bleib dran");
            acts.add(() -> {});
            showSheet(displayName(e), "Verbannt · noch " + remaining(pkg) + ".", null, items, acts);
            return;
        }
        items.add("Lieber nicht");
        acts.add(() -> {});
        items.add("Trotzdem öffnen (10)");
        acts.add(() -> launchNow(e));
        List<TextView> views = showSheet(displayName(e), "Verbannt · noch " + remaining(pkg) + ".", null, items, acts);
        TextView open = views.get(1);
        LinearLayout myPanel = sheetPanel;
        final int[] left = {10};
        Runnable tickDown = new Runnable() {
            @Override public void run() {
                if (sheetPanel != myPanel) return;   // Panel inzwischen geschlossen
                if (left[0] > 0) {
                    open.setEnabled(false);
                    open.setAlpha(0.3f);
                    open.setText("Trotzdem öffnen (" + left[0] + ")");
                    left[0]--;
                    ui.postDelayed(this, 1000);
                } else {
                    open.setEnabled(true);
                    open.animate().alpha(1f).setDuration(200).start();
                    open.setText("Trotzdem öffnen");
                }
            }
        };
        tickDown.run();
    }

    private void rename(AppEntry e) {
        EditText in = new EditText(this);
        in.setText(displayName(e));
        in.setSingleLine(true);
        in.setSelectAllOnFocus(true);
        in.setTextColor(Color.WHITE);
        in.setTextSize(22);
        in.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        GradientDrawable line = new GradientDrawable();
        line.setColor(0x00000000);
        line.setStroke(Math.max(1, px(1)), 0xFF333333);
        in.setBackground(null);
        in.setPadding(0, px(6), 0, px(10));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(in, new LinearLayout.LayoutParams(-1, -2));
        View under = new View(this);
        under.setBackgroundColor(0xFF333333);
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(-1, Math.max(1, px(1)));
        ulp.bottomMargin = px(14);
        box.addView(under, ulp);

        List<String> items = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        items.add("Speichern");
        acts.add(() -> {
            String n = in.getText().toString().trim();
            if (n.isEmpty() || n.equals(e.label)) prefs.edit().remove("name_" + e.key).apply();
            else prefs.edit().putString("name_" + e.key, n).apply();
            loadApps();
        });
        items.add("Abbrechen");
        acts.add(() -> {});
        showSheet("Umbenennen", null, box, items, acts);
        in.requestFocus();
        in.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(in, 0);
        }, 300);
    }

    // ---------- Wisch Startseite <-> alle Apps ----------

    private void setProgress(float p) {
        progress = p;
        float w = home.getWidth();
        drawer.setVisibility(p > 0f ? View.VISIBLE : View.GONE);
        drawer.setTranslationX((1f - p) * w);
        home.setTranslationX(-p * w * 0.25f);
        home.setAlpha(1f - p);
    }

    private void animateTo(float target) {
        if (progressAnim != null) progressAnim.cancel();
        float from = progress;
        ValueAnimator a = ValueAnimator.ofFloat(from, target);
        a.setDuration((long) (120 + 220 * Math.abs(target - from)));
        a.setInterpolator(new DecelerateInterpolator(1.8f));
        a.addUpdateListener(v -> setProgress((float) v.getAnimatedValue()));
        a.addListener(new AnimatorListenerAdapter() {
            boolean cancelled;
            @Override public void onAnimationCancel(Animator x) { cancelled = true; }
            @Override public void onAnimationEnd(Animator x) { if (!cancelled) settle(target >= 0.5f); }
        });
        progressAnim = a;
        a.start();
    }

    private void prepareOpen() {
        search.setText("");
        list.setSelection(0);
        drawer.setVisibility(View.VISIBLE);
    }

    private void settle(boolean open) {
        drawerOpen = open;
        applyNavigationBar();
        if (open) {
            search.requestFocus();
            search.post(() -> {
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(search, 0);
            });
        } else {
            hideKeyboard();
            search.clearFocus();
        }
    }

    private void closeDrawer(boolean animate) {
        hideKeyboard();
        if (animate) {
            animateTo(0f);
        } else {
            if (progressAnim != null) progressAnim.cancel();
            setProgress(0f);
            settle(false);
        }
    }

    private void handleDrag(MotionEvent ev) {
        float w = Math.max(1, home.getWidth());
        float dx = ev.getX() - downX;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                setProgress(Math.max(0f, Math.min(1f, dragStart - dx / w)));
                break;
            case MotionEvent.ACTION_UP: {
                dragging = false;
                velocity.computeCurrentVelocity(1000);
                float vx = velocity.getXVelocity();
                boolean open;
                if (Math.abs(vx) > px(450)) open = vx < 0;
                else open = dragStart == 0f ? progress > 0.3f : progress > 0.7f;
                animateTo(open ? 1f : 0f);
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                animateTo(dragStart);
                break;
        }
    }

    private void cancelChildren(MotionEvent ev) {
        MotionEvent c = MotionEvent.obtain(ev);
        c.setAction(MotionEvent.ACTION_CANCEL);
        super.dispatchTouchEvent(c);
        c.recycle();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (favDrag != null || sheetPanel != null) return super.dispatchTouchEvent(ev);
        int a = ev.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) {
            downX = ev.getX();
            downY = ev.getY();
            dragging = false;
            boolean onAlphabet = drawerOpen && downX > home.getWidth() - px(44);
            dragCandidate = !onAlphabet && (progressAnim == null || !progressAnim.isRunning());
            if (velocity != null) velocity.recycle();
            velocity = VelocityTracker.obtain();
        }
        if (velocity != null) velocity.addMovement(ev);

        if (dragging) {
            handleDrag(ev);
            return true;
        }
        if (a == MotionEvent.ACTION_MOVE && dragCandidate) {
            float dx = ev.getX() - downX, dy = ev.getY() - downY;
            boolean towardDrawer = dx < 0;
            if (Math.abs(dx) > touchSlop * 2 && Math.abs(dx) > Math.abs(dy) * 1.5f
                    && (drawerOpen != towardDrawer)) {
                dragging = true;
                dragStart = drawerOpen ? 1f : 0f;
                if (drawerOpen) hideKeyboard(); else prepareOpen();
                cancelChildren(ev);
                handleDrag(ev);
                return true;
            }
            if (Math.abs(dy) > touchSlop * 2 && Math.abs(dy) > Math.abs(dx)) dragCandidate = false;
        }
        if (!drawerOpen && gestures.onTouchEvent(ev) && a == MotionEvent.ACTION_UP) {
            cancelChildren(ev);
            return true;
        }
        return super.dispatchTouchEvent(ev);
    }

    // ---------- System ----------

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
    }

    /** Home-/Zurück-Leiste ausblenden; Wisch vom unteren Rand blendet sie kurz ein. */
    @SuppressWarnings("deprecation")
    private void showNavigationBar() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) c.show(WindowInsets.Type.navigationBars());
        } else {
            getWindow().getDecorView().setSystemUiVisibility(0);
        }
    }

    /** Leiste nur auf der Startseite ausblenden – in der App-Liste reagiert Home so sofort. */
    private void applyNavigationBar() {
        if (drawerOpen) showNavigationBar(); else hideNavigationBar();
    }

    private void hideNavigationBar() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                c.hide(WindowInsets.Type.navigationBars());
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    @SuppressWarnings("JavaReflectionMemberAccess")
    private void expandNotifications() {
        try {
            Object sb = getSystemService("statusbar");
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(sb);
        } catch (Exception ignored) {
        }
    }

    private void register(BroadcastReceiver r, IntentFilter f) {
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(r, f);
    }

    // ---------- Lebenszyklus ----------

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyNavigationBar();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        register(battery, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        IntentFilter t = new IntentFilter(Intent.ACTION_TIME_TICK);
        t.addAction(Intent.ACTION_TIME_CHANGED);
        t.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        register(tick, t);
        clock.updateTime();
        clock.setActive(true);
        applyNavigationBar();
        if (introPending) {
            introPending = false;
            clock.playIntro();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        clock.setActive(false);
        unregisterReceiver(battery);
        unregisterReceiver(tick);
    }

    @Override
    protected void onStop() {
        super.onStop();
        closeSheet(false);
        closeDrawer(false);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (drawerOpen || progress > 0f) closeDrawer(true);
    }

    @Override
    public void onBackPressed() {
        if (sheetPanel != null) { closeSheet(true); return; }
        if (drawerOpen || progress > 0f) closeDrawer(true);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(packages);
        unregisterReceiver(unlocked);
    }
}
