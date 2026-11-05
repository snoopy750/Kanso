package ch.sinan.schlicht;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
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
        @Override public void onReceive(Context c, Intent i) { clock.updateTime(); }
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

        FrameLayout root = new FrameLayout(this);
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
            t.setText(displayName(shown.get(p)));
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
            t.setText(displayName(e));
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
        items.add("Umbenennen");
        acts.add(() -> rename(e));
        Uri pkg = Uri.parse("package:" + e.cn.getPackageName());
        items.add("App-Info");
        acts.add(() -> start(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)));
        items.add("Deinstallieren");
        acts.add(() -> start(new Intent(Intent.ACTION_DELETE, pkg)));

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(displayName(e))
                .setItems(items.toArray(new String[0]), (d, w) -> acts.get(w).run())
                .show();
    }

    private void rename(AppEntry e) {
        EditText in = new EditText(this);
        in.setText(displayName(e));
        in.setSingleLine(true);
        in.setSelectAllOnFocus(true);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(px(20), px(8), px(20), 0);
        wrap.addView(in);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Umbenennen")
                .setView(wrap)
                .setPositiveButton("OK", (d, w) -> {
                    String n = in.getText().toString().trim();
                    if (n.isEmpty() || n.equals(e.label)) prefs.edit().remove("name_" + e.key).apply();
                    else prefs.edit().putString("name_" + e.key, n).apply();
                    loadApps();
                })
                .setNegativeButton("Abbrechen", null)
                .show();
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
        if (favDrag != null) return super.dispatchTouchEvent(ev);
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
        if (hasFocus) hideNavigationBar();
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
        hideNavigationBar();
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
        closeDrawer(false);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (drawerOpen || progress > 0f) closeDrawer(true);
    }

    @Override
    public void onBackPressed() {
        if (drawerOpen || progress > 0f) closeDrawer(true);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(packages);
        unregisterReceiver(unlocked);
    }
}
