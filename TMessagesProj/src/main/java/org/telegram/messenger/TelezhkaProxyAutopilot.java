/*
 * This is the source code of Telegram for Android v. 7.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.messenger;

import android.content.SharedPreferences;
import android.os.SystemClock;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ProxyPickerActivity;
import org.telegram.utils.proxy.ProxySettings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

/**
 * Keeps proxies working without the user doing anything:
 * - every 30 minutes (and soon after start) downloads the fresh list, adds new proxies,
 *   checks all of them and removes the ones that failed two checks in a row;
 * - switches to the fastest spare when the current proxy stops answering or gets much slower;
 * - turns a proxy on by itself when Telegram cannot connect directly for a while.
 * Can be switched off on the "Подключение" screen.
 */
public class TelezhkaProxyAutopilot implements NotificationCenter.NotificationCenterDelegate {

    public static final String PREF_ENABLED = "telezhka_autopilot";
    public static final String PREF_LAST_RUN = "telezhka_autopilot_last";
    public static final String PREF_ADDED = "telezhka_autopilot_added";
    public static final String PREF_REMOVED = "telezhka_autopilot_removed";

    private static final long PERIOD_MS = 30 * 60 * 1000L;
    private static final long FIRST_RUN_DELAY_MS = 20 * 1000L;
    private static final long STUCK_MS = 20 * 1000L;
    private static final long URGENT_THROTTLE_MS = 90 * 1000L;
    private static final long CHECK_TIMEOUT_MS = 7000;
    private static final int PARALLEL_CHECKS = 12;
    private static final int MAX_PROXIES = 40;
    private static final int FAILS_TO_REMOVE = 2;

    private static final TelezhkaProxyAutopilot INSTANCE = new TelezhkaProxyAutopilot();

    private final HashMap<ProxySettings, Integer> failures = new HashMap<>();
    private boolean running;
    private long lastUrgentRun;
    private final Runnable periodic = () -> run(false);
    private final Runnable stuckCheck = this::onStuck;

    public static void init() {
        INSTANCE.initInternal();
    }

    public static boolean isEnabled() {
        return MessagesController.getGlobalMainSettings().getBoolean(PREF_ENABLED, true);
    }

    public static void setEnabled(boolean enabled) {
        MessagesController.getGlobalMainSettings().edit().putBoolean(PREF_ENABLED, enabled).apply();
        if (enabled) {
            INSTANCE.schedule(1000);
        }
    }

    // Run now, e.g. from the "Подключение" screen
    public static void runNow() {
        INSTANCE.run(true);
    }

    private void initInternal() {
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            NotificationCenter.getInstance(i).addObserver(this, NotificationCenter.didUpdateConnectionState);
        }
        schedule(FIRST_RUN_DELAY_MS);
    }

    private void schedule(long delay) {
        AndroidUtilities.cancelRunOnUIThread(periodic);
        AndroidUtilities.runOnUIThread(periodic, delay);
    }

    private static boolean isConnected() {
        int state = ConnectionsManager.getInstance(UserConfig.selectedAccount).getConnectionState();
        return state == ConnectionsManager.ConnectionStateConnected || state == ConnectionsManager.ConnectionStateUpdating;
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id != NotificationCenter.didUpdateConnectionState || account != UserConfig.selectedAccount) {
            return;
        }
        int state = ConnectionsManager.getInstance(account).getConnectionState();
        if (state == ConnectionsManager.ConnectionStateConnecting || state == ConnectionsManager.ConnectionStateConnectingToProxy) {
            AndroidUtilities.cancelRunOnUIThread(stuckCheck);
            AndroidUtilities.runOnUIThread(stuckCheck, STUCK_MS);
        } else {
            AndroidUtilities.cancelRunOnUIThread(stuckCheck);
        }
    }

    // Still not connected after a while: Telegram's own rotation did not help, act now
    private void onStuck() {
        if (!isEnabled() || isConnected()) {
            return;
        }
        int state = ConnectionsManager.getInstance(UserConfig.selectedAccount).getConnectionState();
        if (state == ConnectionsManager.ConnectionStateWaitingForNetwork) {
            return;  // no internet at all, proxies will not help
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastUrgentRun < URGENT_THROTTLE_MS) {
            return;
        }
        lastUrgentRun = now;
        run(true);
    }

    private void run(boolean urgent) {
        if (!urgent) {
            schedule(PERIOD_MS);
        }
        if (!isEnabled() || running) {
            return;
        }
        running = true;
        Utilities.externalNetworkQueue.postRunnable(() -> {
            ArrayList<ProxySettings> fresh = new ArrayList<>();
            for (String url : ProxyPickerActivity.getListUrls()) {
                fresh = ProxyPickerActivity.parseProxyList(ProxyPickerActivity.downloadList(url));
                if (!fresh.isEmpty()) {
                    break;
                }
            }
            if (fresh.isEmpty()) {
                fresh = ProxyPickerActivity.loadFallbackList();
            }
            final ArrayList<ProxySettings> list = fresh;
            AndroidUtilities.runOnUIThread(() -> merge(list, urgent));
        });
    }

    private void merge(ArrayList<ProxySettings> fresh, boolean urgent) {
        SharedConfig.loadProxyList();
        HashSet<ProxySettings> known = new HashSet<>();
        for (SharedConfig.ProxyInfo info : SharedConfig.proxyList) {
            known.add(info.settings);
        }
        int added = 0;
        for (ProxySettings settings : fresh) {
            if (SharedConfig.proxyList.size() >= MAX_PROXIES) {
                break;
            }
            if (known.add(settings)) {
                SharedConfig.proxyList.add(new SharedConfig.ProxyInfo(settings));
                added++;
            }
        }
        if (SharedConfig.proxyList.isEmpty()) {
            running = false;
            return;
        }
        checkAll(added, urgent);
    }

    private void checkAll(int added, boolean urgent) {
        final ArrayList<SharedConfig.ProxyInfo> queue = new ArrayList<>(SharedConfig.proxyList);
        final int[] next = {0};
        final int[] active = {0};
        final int[] responded = {0};
        final HashMap<SharedConfig.ProxyInfo, Long> results = new HashMap<>();
        final Runnable[] pump = new Runnable[1];
        pump[0] = () -> {
            while (active[0] < PARALLEL_CHECKS && next[0] < queue.size()) {
                SharedConfig.ProxyInfo info = queue.get(next[0]++);
                active[0]++;
                final boolean[] done = {false};
                final Runnable timeout = () -> {
                    if (!done[0]) {
                        done[0] = true;
                        results.put(info, -1L);
                        active[0]--;
                        pump[0].run();
                    }
                };
                AndroidUtilities.runOnUIThread(timeout, CHECK_TIMEOUT_MS);
                ConnectionsManager.getInstance(UserConfig.selectedAccount).checkProxy(info.settings, time -> AndroidUtilities.runOnUIThread(() -> {
                    if (done[0]) {
                        return;
                    }
                    done[0] = true;
                    AndroidUtilities.cancelRunOnUIThread(timeout);
                    responded[0]++;
                    results.put(info, time);
                    active[0]--;
                    pump[0].run();
                }));
            }
            if (active[0] == 0 && next[0] >= queue.size()) {
                finish(results, responded[0] > 0, added, urgent);
            }
        };
        pump[0].run();
    }

    private void finish(HashMap<SharedConfig.ProxyInfo, Long> results, boolean online, int added, boolean urgent) {
        running = false;
        long now = SystemClock.elapsedRealtime();
        int removed = 0;
        SharedConfig.ProxyInfo best = null;
        ArrayList<SharedConfig.ProxyInfo> toRemove = new ArrayList<>();
        for (java.util.Map.Entry<SharedConfig.ProxyInfo, Long> entry : results.entrySet()) {
            SharedConfig.ProxyInfo info = entry.getKey();
            long ping = entry.getValue();
            info.availableCheckTime = now;
            info.checking = false;
            if (ping > 0) {
                info.ping = ping;
                info.available = true;
                failures.remove(info.settings);
                if (best == null || ping < best.ping) {
                    best = info;
                }
            } else {
                info.available = false;
                info.ping = 0;
                if (online) {
                    // Only count a failure when other proxies did answer: otherwise the network itself is down
                    int count = failures.containsKey(info.settings) ? failures.get(info.settings) + 1 : 1;
                    failures.put(info.settings, count);
                    if (count >= FAILS_TO_REMOVE && info != SharedConfig.currentProxy) {
                        toRemove.add(info);
                    }
                }
            }
        }
        for (SharedConfig.ProxyInfo info : toRemove) {
            SharedConfig.proxyList.remove(info);
            failures.remove(info.settings);
            removed++;
        }

        SharedConfig.ProxyInfo current = SharedConfig.currentProxy;
        boolean proxyOn = SharedConfig.isProxyEnabled();
        if (best != null) {
            if (!proxyOn) {
                // Only turn a proxy on when Telegram cannot connect without one
                if (urgent && !isConnected()) {
                    enable(best);
                }
            } else if (current == null || !current.available) {
                enable(best);
            } else if (best != current && current.ping > 400 && best.ping * 2 < current.ping) {
                enable(best);
            }
        } else if (!online && urgent && !proxyOn && !isConnected() && !SharedConfig.proxyList.isEmpty()) {
            // Checks need a first connection; without one, turn on the first proxy and let rotation try the rest
            SharedConfig.proxyRotationEnabled = true;
            SharedConfig.saveConfig();
            enable(SharedConfig.proxyList.get(0));
        }

        SharedConfig.saveProxyList();
        MessagesController.getGlobalMainSettings().edit()
                .putLong(PREF_LAST_RUN, System.currentTimeMillis())
                .putInt(PREF_ADDED, added)
                .putInt(PREF_REMOVED, removed)
                .apply();
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
    }

    private static void enable(SharedConfig.ProxyInfo info) {
        SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();
        info.settings.toSharedPreferences(editor);
        editor.putBoolean("proxy_enabled", true);
        editor.apply();
        SharedConfig.currentProxy = info;
        ConnectionsManager.setProxySettings(true, info.settings);
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
        TelezhkaPingMonitor.refresh();
    }
}
