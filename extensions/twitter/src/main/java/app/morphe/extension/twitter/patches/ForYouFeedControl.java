/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.patches;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.twitter.settings.Settings;

/**
 * Applies the optional For You time windows without changing X's ranking or feed data.
 * The list is made non-interactive when its daily window is closed; post details already
 * open at that point remain usable, while new detail activities are rejected.
 */
@SuppressWarnings("unused")
public final class ForYouFeedControl {
    private static final String MAIN_ACTIVITY = "com.twitter.app.main.MainActivity";
    private static final String TWEET_DETAIL_ACTIVITY = "com.twitter.tweetdetail.TweetDetailActivity";
    private static final String[] EXTERNAL_BROWSER_ACTIVITIES = {
            "com.twitter.browser.BrowserActivity",
            "com.twitter.android.webview.WebViewActivity",
            "com.twitter.android.client.web.AuthenticatedWebViewActivity",
            "com.twitter.android.client.web.AuthenticatedTwitterSubdomainWebViewActivity",
            "com.twitter.revenue.BrowserWithDockedMediaActivity",
            "com.twitter.revenue.playable.navigation.fullscreen.BrowserWithBottomBarActivity"
    };

    private static final long WINDOW_LIMIT_MS = 10L * 60L * 1000L;
    private static final int MODE_NONE = 0;
    private static final int MODE_FEED = 1;
    private static final int MODE_POST = 2;
    private static final int TICK_MS = 1000;
    private static final String PREF_WINDOW = Settings.PRODUCTIVITY_FEED_WINDOW_KEY;
    private static final String PREF_USED_MS = Settings.PRODUCTIVITY_FEED_USED_MS_KEY;
    private static final String PREF_REFRESHED_WINDOW = Settings.PRODUCTIVITY_FEED_REFRESHED_WINDOW_KEY;
    private static final String EMPTY_FEED_MESSAGE = "Aucun tweet disponible";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WeakReference<RecyclerView> forYouList = new WeakReference<>(null);
    private static WeakReference<RecyclerView> followingList = new WeakReference<>(null);
    private static WeakReference<Object> feedController = new WeakReference<>(null);
    private static WeakReference<Activity> currentActivity = new WeakReference<>(null);
    private static WeakReference<Activity> frozenDetail = new WeakReference<>(null);
    private static final Map<RecyclerView, FeedGate> feedGates =
            Collections.synchronizedMap(new WeakHashMap<RecyclerView, FeedGate>());
    private static final Map<View, NavigationControlState> blockedNavigationControls =
            Collections.synchronizedMap(new WeakHashMap<View, NavigationControlState>());
    private static Application application;
    private static SharedPreferences preferences;
    private static boolean initialized;
    private static boolean fromForYouStack;
    private static boolean currentDetailFromForYou;
    private static int mode = MODE_NONE;
    private static long lastTickElapsed;

    private static final Runnable TICK = new Runnable() {
        @Override
        public void run() {
            tick();
        }
    };

    private ForYouFeedControl() { }

    public static synchronized void initialize(Application app) {
        if (initialized || app == null) return;
        application = app;
        preferences = app.getSharedPreferences(Settings.SHARED_PREF_NAME, Context.MODE_PRIVATE);
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, Bundle state) {
                closeBlockedOvernightActivity(activity);
                if (isTweetDetail(activity) && shouldBlockNewPost() && frozenDetail.get() != activity) activity.finish();
            }
            @Override public void onActivityStarted(Activity activity) { }
            @Override public void onActivityResumed(Activity activity) {
                advanceUsage();
                currentActivity = new WeakReference<>(activity);
                closeBlockedOvernightActivity(activity);
                applyFeedGate();
                applyOvernightNavigationGate();
                updateActivityMode();
                if (isEnabled() && (isMainActivity(activity) || isTweetDetail(activity))) scheduleTick();
                else {
                    MAIN.removeCallbacks(TICK);
                    lastTickElapsed = 0L;
                }
                if (isTweetDetail(activity) && shouldBlockNewPost() && frozenDetail.get() != activity) activity.finish();
            }
            @Override public void onActivityPaused(Activity activity) {
                if (currentActivity.get() == activity) {
                    advanceUsage();
                    if (isMainActivity(activity)) {
                        fromForYouStack = isForYouPageVisible(activity);
                        currentDetailFromForYou = false;
                    } else if (isTweetDetail(activity)) {
                        fromForYouStack = currentDetailFromForYou;
                    } else if (!isExternalBrowser(activity)) {
                        fromForYouStack = false;
                        currentDetailFromForYou = false;
                    }
                    currentActivity.clear();
                    mode = MODE_NONE;
                    MAIN.removeCallbacks(TICK);
                    lastTickElapsed = 0L;
                }
            }
            @Override public void onActivityStopped(Activity activity) { }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
            @Override public void onActivityDestroyed(Activity activity) {
                if (frozenDetail.get() == activity) frozenDetail.clear();
            }
        });
        initialized = true;
    }

    /** Called by the one-way settings switch. */
    public static synchronized void activate() {
        SharedPreferences prefs = preferences;
        if (prefs == null && application != null) {
            prefs = application.getSharedPreferences(Settings.SHARED_PREF_NAME, Context.MODE_PRIVATE);
            preferences = prefs;
        }
        if (prefs != null) {
            prefs.edit()
                    .putBoolean(Settings.PRODUCTIVITY_FEED_LIMIT.key, true)
                    .putBoolean(Settings.PRODUCTIVITY_FEED_LIMIT_LOCKED.key, true)
                    .apply();
        }
        applyFeedGate();
        applyOvernightNavigationGate();
        updateActivityMode();
        scheduleTick();
    }

    public static boolean isLocked() {
        SharedPreferences prefs = preferences;
        return prefs != null && (prefs.getBoolean(Settings.PRODUCTIVITY_FEED_LIMIT_LOCKED.key, false)
                || prefs.getBoolean(Settings.PRODUCTIVITY_FEED_LIMIT.key, false));
    }

    /** Receives each home timeline controller and its RecyclerView from the 12.19.1 patch hook. */
    public static void attachTimeline(Object controller, RecyclerView recyclerView) {
        if (controller == null || recyclerView == null) return;
        if (isForYouController(controller)) {
            feedController = new WeakReference<>(controller);
            forYouList = new WeakReference<>(recyclerView);
        } else {
            followingList = new WeakReference<>(recyclerView);
        }
        gateFor(recyclerView);
        applyFeedGate();
        updateActivityMode();
        if (isEnabled()) scheduleTick();
    }

    /** Guard for TweetDetailActivity.onNewIntent, which otherwise can replace the open post. */
    public static boolean shouldBlockNewPost() {
        return isEnabled() && (isOvernightClosed() || (fromForYouStack && isWindowClosed()));
    }

    /** Guard for X's own refresh callback; the scheduled window refresh uses this same method. */
    public static boolean canRefresh(Object controller) {
        if (!isEnabled()) return true;
        if (isOvernightClosed()) return false;
        return !isForYouController(controller) || !isWindowClosed();
    }

    private static boolean isEnabled() {
        SharedPreferences prefs = preferences;
        if (prefs == null) return false;
        boolean locked = prefs.getBoolean(Settings.PRODUCTIVITY_FEED_LIMIT_LOCKED.key, false);
        boolean enabled = prefs.getBoolean(Settings.PRODUCTIVITY_FEED_LIMIT.key, false);
        if (enabled && !locked) {
            prefs.edit().putBoolean(Settings.PRODUCTIVITY_FEED_LIMIT_LOCKED.key, true).apply();
            return true;
        }
        return locked || enabled;
    }

    private static void scheduleTick() {
        if (!initialized || !isEnabled()) return;
        if (lastTickElapsed == 0L) lastTickElapsed = SystemClock.elapsedRealtime();
        MAIN.removeCallbacks(TICK);
        MAIN.postDelayed(TICK, TICK_MS);
    }

    private static void tick() {
        if (!isEnabled()) {
            MAIN.removeCallbacks(TICK);
            mode = MODE_NONE;
            return;
        }
        advanceUsage();
        applyFeedGate();
        applyOvernightNavigationGate();
        updateActivityMode();
        maybeRefreshForWindow();
        MAIN.postDelayed(TICK, TICK_MS);
    }

    private static void advanceUsage() {
        long now = SystemClock.elapsedRealtime();
        if (lastTickElapsed == 0L) {
            lastTickElapsed = now;
            return;
        }
        long elapsed = now - lastTickElapsed;
        lastTickElapsed = now;
        if (elapsed <= 0L || !isEnabled()) return;

        // A long UI pause or device sleep must not be counted as active reading time.
        elapsed = Math.min(elapsed, 1500L);
        Session session = currentSession();
        if (session == null) {
            freezeCurrentDetailIfNeeded();
            return;
        }
        if (mode == MODE_NONE || !hasWindowFocus(currentActivity.get())) return;

        SharedPreferences prefs = preferences;
        long used = prefs.getLong(PREF_USED_MS, 0L);
        long weighted = mode == MODE_POST ? elapsed / 2L : elapsed;
        long updated = Math.min(WINDOW_LIMIT_MS, used + weighted);
        prefs.edit().putLong(PREF_USED_MS, updated).apply();
        if (updated >= WINDOW_LIMIT_MS) freezeCurrentDetailIfNeeded();
    }

    private static void freezeCurrentDetailIfNeeded() {
        Activity activity = currentActivity.get();
        if (isTweetDetail(activity) && currentDetailFromForYou) {
            frozenDetail = new WeakReference<>(activity);
        }
    }

    private static void updateActivityMode() {
        Activity activity = currentActivity.get();
        if (!isEnabled() || activity == null || !hasWindowFocus(activity)) {
            mode = MODE_NONE;
            return;
        }
        if (isMainActivity(activity)) {
            boolean feedVisible = isForYouPageSelected(activity) && !hasVisibleWebView(activity.getWindow().getDecorView());
            mode = feedVisible ? MODE_FEED : MODE_NONE;
            fromForYouStack = feedVisible;
            currentDetailFromForYou = false;
        } else if (isTweetDetail(activity)) {
            currentDetailFromForYou = fromForYouStack;
            mode = currentDetailFromForYou && !hasVisibleWebView(activity.getWindow().getDecorView()) ? MODE_POST : MODE_NONE;
        } else {
            // External browser activities and every other X screen are outside the allowance.
            mode = MODE_NONE;
        }
    }

    private static void applyFeedGate() {
        if (!isEnabled()) return;
        applyListGate(forYouList.get(), isWindowClosed());
        applyListGate(followingList.get(), isOvernightClosed());
    }

    /**
     * X makes recycled timeline views visible again after an asynchronous response. The gate is
     * therefore reapplied on every tick and uses a separate touch-blocking message view.
     */
    private static void applyListGate(RecyclerView list, boolean blocked) {
        if (list == null) return;
        FeedGate gate = gateFor(list);
        if (blocked) {
            list.setEnabled(false);
            if (list.getVisibility() != View.INVISIBLE) list.setVisibility(View.INVISIBLE);
            gate.blocked = true;
            showEmptyFeedMessage(gate, list);
        } else if (gate.blocked) {
            list.setEnabled(gate.enabledBeforeBlock);
            list.setVisibility(View.VISIBLE);
            hideEmptyFeedMessage(gate);
            gate.blocked = false;
        }
    }

    private static FeedGate gateFor(RecyclerView list) {
        FeedGate gate = feedGates.get(list);
        if (gate == null) {
            gate = new FeedGate(list.isEnabled());
            feedGates.put(list, gate);
        }
        return gate;
    }

    private static void showEmptyFeedMessage(FeedGate gate, RecyclerView list) {
        FrameLayout host = findOverlayHost(list);
        if (host == null || list.getWidth() <= 0 || list.getHeight() <= 0) return;
        if (gate.message == null || gate.message.getParent() != host) {
            if (gate.message != null && gate.message.getParent() instanceof ViewGroup) {
                ((ViewGroup) gate.message.getParent()).removeView(gate.message);
            }
            TextView message = new TextView(host.getContext());
            message.setText(EMPTY_FEED_MESSAGE);
            message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            message.setTextColor(0xFF536471);
            message.setGravity(Gravity.CENTER);
            message.setClickable(true);
            message.setOnTouchListener((view, event) -> true);
            host.addView(message);
            gate.message = message;
        }

        int[] listLocation = new int[2];
        int[] hostLocation = new int[2];
        list.getLocationInWindow(listLocation);
        host.getLocationInWindow(hostLocation);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(list.getWidth(), list.getHeight());
        params.leftMargin = listLocation[0] - hostLocation[0];
        params.topMargin = listLocation[1] - hostLocation[1];
        gate.message.setLayoutParams(params);
        gate.message.setVisibility(View.VISIBLE);
        gate.message.bringToFront();
    }

    private static void hideEmptyFeedMessage(FeedGate gate) {
        if (gate.message != null) gate.message.setVisibility(View.GONE);
    }

    private static FrameLayout findOverlayHost(View view) {
        ViewParent parent = view.getParent();
        while (parent instanceof View) {
            if (parent instanceof FrameLayout) return (FrameLayout) parent;
            parent = parent.getParent();
        }
        return null;
    }

    private static void maybeRefreshForWindow() {
        if (mode != MODE_FEED || preferences == null) return;
        Activity activity = currentActivity.get();
        if (!isMainActivity(activity) || !isForYouPageSelected(activity)) return;
        Session session = currentSession();
        if (session == null || isWindowClosed()) return;
        if (session.key.equals(preferences.getString(PREF_REFRESHED_WINDOW, ""))) return;

        Object controller = feedController.get();
        if (controller == null) return;
        try {
            Method refresh = controller.getClass().getMethod("P");
            refresh.invoke(controller);
            preferences.edit().putString(PREF_REFRESHED_WINDOW, session.key).apply();
        } catch (Exception ignored) {
            // Keep the window eligible for a retry if X's controller is not ready yet.
        }
    }

    private static boolean isWindowClosed() {
        Session session = currentSession();
        if (session == null) return true;
        SharedPreferences prefs = preferences;
        return prefs == null || prefs.getLong(PREF_USED_MS, 0L) >= WINDOW_LIMIT_MS;
    }

    private static boolean isOvernightClosed() {
        return Calendar.getInstance().get(Calendar.HOUR_OF_DAY) < 6;
    }

    private static Session currentSession() {
        if (preferences == null) return null;
        Calendar now = Calendar.getInstance();
        int hour = now.get(Calendar.HOUR_OF_DAY);
        if (hour < 6) return null;
        int startHour = hour < 12 ? 6 : (hour < 18 ? 12 : 18);
        String key = String.format(Locale.US, "%04d-%02d-%02d-%02d",
                now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH), startHour);
        String storedKey = preferences.getString(PREF_WINDOW, "");
        if (!key.equals(storedKey)) {
            preferences.edit().putString(PREF_WINDOW, key).putLong(PREF_USED_MS, 0L).apply();
            frozenDetail.clear();
        }
        return new Session(key);
    }

    private static boolean isForYouController(Object controller) {
        try {
            Field type = findField(controller.getClass(), "B");
            return type != null && type.getInt(controller) == 0x11;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // Continue through X's base timeline classes.
            }
        }
        return null;
    }

    private static boolean isForYouPageSelected(Activity activity) {
        return hasWindowFocus(activity) && isForYouPageVisible(activity);
    }

    private static boolean isForYouPageVisible(Activity activity) {
        RecyclerView list = forYouList.get();
        if (list == null || activity == null || activity.getWindow() == null) return false;
        View decor = activity.getWindow().getDecorView();
        if (list.getRootView() != decor.getRootView()) return false;

        ViewParent parent = list.getParent();
        while (parent instanceof View) {
            View parentView = (View) parent;
            if (parentView.getVisibility() != View.VISIBLE || parentView.getWindowVisibility() != View.VISIBLE) return false;
            if (parentView == decor) break;
            parent = parentView.getParent();
        }

        if (list.getVisibility() == View.VISIBLE) {
            Rect visibleBounds = new Rect();
            return list.getGlobalVisibleRect(visibleBounds) && visibleBounds.height() > 0 && visibleBounds.width() > 0;
        }
        return false;
    }

    /** Closes dedicated activity screens that can bypass the main navigation bar at night. */
    private static void closeBlockedOvernightActivity(Activity activity) {
        if (!isEnabled() || !isOvernightClosed() || activity == null || activity.isFinishing()) return;
        String activityName = activity.getClass().getName().toLowerCase(Locale.ROOT);
        if (activityName.contains("bookmark") || activityName.contains("search")
                || activityName.contains("explore") || activityName.contains("trend")) {
            activity.finish();
        }
    }

    /**
     * Search and Explore commonly live inside MainActivity rather than in a separate Activity.
     * Their buttons are disabled while the overnight gate is active; the original state returns at
     * 06:00 without changing the user's navigation customisation.
     */
    private static void applyOvernightNavigationGate() {
        if (!isEnabled() || !isOvernightClosed()) {
            restoreNavigationControls();
            return;
        }

        Activity activity = currentActivity.get();
        closeBlockedOvernightActivity(activity);
        if (!isMainActivity(activity) || activity.getWindow() == null) return;
        blockNavigationControls(activity.getWindow().getDecorView());
    }

    private static void blockNavigationControls(View view) {
        if (view == null) return;
        if (isBlockedNavigationLabel(view)) blockNavigationControl(clickableAncestor(view));
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                blockNavigationControls(group.getChildAt(index));
            }
        }
    }

    private static boolean isBlockedNavigationLabel(View view) {
        StringBuilder label = new StringBuilder();
        CharSequence description = view.getContentDescription();
        if (description != null) label.append(description).append(' ');
        if (view instanceof TextView) label.append(((TextView) view).getText()).append(' ');
        if (view.getId() != View.NO_ID) {
            try {
                label.append(view.getResources().getResourceEntryName(view.getId()));
            } catch (Exception ignored) {
                // Programmatic and generated identifiers have no resource entry.
            }
        }
        String normalized = label.toString().toLowerCase(Locale.ROOT);
        return normalized.contains("bookmark") || normalized.contains("signet")
                || normalized.contains("search") || normalized.contains("recherch")
                || normalized.contains("explor") || normalized.contains("trend")
                || normalized.contains("tendance");
    }

    private static View clickableAncestor(View view) {
        View candidate = view;
        for (int depth = 0; depth < 4; depth++) {
            if (candidate.isClickable() || candidate.hasOnClickListeners()) return candidate;
            ViewParent parent = candidate.getParent();
            if (!(parent instanceof View)) break;
            candidate = (View) parent;
        }
        return view;
    }

    private static void blockNavigationControl(View control) {
        if (control == null || blockedNavigationControls.containsKey(control)) return;
        blockedNavigationControls.put(control,
                new NavigationControlState(control.isEnabled(), control.isClickable(), control.getAlpha()));
        control.setEnabled(false);
        control.setClickable(false);
        control.setAlpha(Math.min(control.getAlpha(), 0.35f));
    }

    private static void restoreNavigationControls() {
        if (blockedNavigationControls.isEmpty()) return;
        ArrayList<Map.Entry<View, NavigationControlState>> controls =
                new ArrayList<>(blockedNavigationControls.entrySet());
        blockedNavigationControls.clear();
        for (Map.Entry<View, NavigationControlState> entry : controls) {
            View control = entry.getKey();
            NavigationControlState state = entry.getValue();
            if (control == null || state == null) continue;
            control.setEnabled(state.enabled);
            control.setClickable(state.clickable);
            control.setAlpha(state.alpha);
        }
    }

    private static boolean hasWindowFocus(Activity activity) {
        return activity != null && !activity.isFinishing() && activity.getWindow() != null
                && activity.getWindow().getDecorView().hasWindowFocus();
    }

    private static boolean hasVisibleWebView(View view) {
        if (view == null || view.getVisibility() != View.VISIBLE || view.getWindowVisibility() != View.VISIBLE) return false;
        if (view instanceof WebView) {
            Rect visibleBounds = new Rect();
            return view.getGlobalVisibleRect(visibleBounds) && visibleBounds.width() > 0 && visibleBounds.height() > 0;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                if (hasVisibleWebView(group.getChildAt(index))) return true;
            }
        }
        return false;
    }

    private static boolean isMainActivity(Activity activity) {
        return activity != null && MAIN_ACTIVITY.equals(activity.getClass().getName());
    }

    private static boolean isTweetDetail(Activity activity) {
        return activity != null && TWEET_DETAIL_ACTIVITY.equals(activity.getClass().getName());
    }

    private static boolean isExternalBrowser(Activity activity) {
        if (activity == null) return false;
        String name = activity.getClass().getName();
        String normalized = name.toLowerCase(Locale.ROOT);
        if (normalized.contains("webview") || normalized.contains("browser")) return true;
        for (String browser : EXTERNAL_BROWSER_ACTIVITIES) {
            if (browser.equals(name)) return true;
        }
        return false;
    }

    private static final class FeedGate {
        final boolean enabledBeforeBlock;
        TextView message;
        boolean blocked;

        FeedGate(boolean enabledBeforeBlock) {
            this.enabledBeforeBlock = enabledBeforeBlock;
        }
    }

    private static final class NavigationControlState {
        final boolean enabled;
        final boolean clickable;
        final float alpha;

        NavigationControlState(boolean enabled, boolean clickable, float alpha) {
            this.enabled = enabled;
            this.clickable = clickable;
            this.alpha = alpha;
        }
    }

    private static final class Session {
        @SuppressWarnings("unused")
        final String key;

        Session(String key) {
            this.key = key;
        }
    }
}
