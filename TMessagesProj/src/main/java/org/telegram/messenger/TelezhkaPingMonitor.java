/*
 * This is the source code of Telegram for Android v. 7.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.messenger;

import org.telegram.tgnet.ConnectionsManager;

import java.util.ArrayList;

/**
 * Measures the ping of the current proxy while someone is listening:
 * the chat list header pill (slow) and the connection screen (fast).
 */
public class TelezhkaPingMonitor {

    public static final int MAX_HISTORY = 40;

    public interface Listener {
        // ping in ms, or -1 when there is no proxy or it does not answer
        void onPing(long ping);
    }

    private static final ArrayList<Listener> listeners = new ArrayList<>();
    private static final ArrayList<Long> intervals = new ArrayList<>();
    private static final ArrayList<Long> history = new ArrayList<>();
    private static long lastPing = -1;
    private static boolean checking;
    private static Runnable scheduled;

    public static long getLastPing() {
        return lastPing;
    }

    public static ArrayList<Long> getHistory() {
        return history;
    }

    public static void start(Listener listener, long intervalMs) {
        if (listeners.contains(listener)) {
            return;
        }
        listeners.add(listener);
        intervals.add(intervalMs);
        listener.onPing(lastPing);
        reschedule(0);
    }

    public static void stop(Listener listener) {
        int index = listeners.indexOf(listener);
        if (index < 0) {
            return;
        }
        listeners.remove(index);
        intervals.remove(index);
        if (listeners.isEmpty() && scheduled != null) {
            AndroidUtilities.cancelRunOnUIThread(scheduled);
            scheduled = null;
        }
    }

    // Measure right now, e.g. after the proxy changed
    public static void refresh() {
        if (!listeners.isEmpty()) {
            reschedule(0);
        }
    }

    private static long currentInterval() {
        long min = Long.MAX_VALUE;
        for (Long interval : intervals) {
            min = Math.min(min, interval);
        }
        return min;
    }

    private static void reschedule(long delay) {
        if (scheduled != null) {
            AndroidUtilities.cancelRunOnUIThread(scheduled);
        }
        scheduled = TelezhkaPingMonitor::tick;
        AndroidUtilities.runOnUIThread(scheduled, delay);
    }

    private static void tick() {
        scheduled = null;
        if (listeners.isEmpty() || checking) {
            return;
        }
        SharedConfig.ProxyInfo proxy = SharedConfig.currentProxy;
        if (!SharedConfig.isProxyEnabled() || proxy == null) {
            publish(-1, false);
            reschedule(currentInterval());
            return;
        }
        checking = true;
        final long timeoutAt = 8000;
        final Runnable timeout = () -> {
            if (checking) {
                checking = false;
                publish(-1, true);
                reschedule(currentInterval());
            }
        };
        AndroidUtilities.runOnUIThread(timeout, timeoutAt);
        ConnectionsManager.getInstance(UserConfig.selectedAccount).checkProxy(proxy.settings, time -> AndroidUtilities.runOnUIThread(() -> {
            if (!checking) {
                return;
            }
            AndroidUtilities.cancelRunOnUIThread(timeout);
            checking = false;
            if (time > 0) {
                proxy.ping = time;
                proxy.available = true;
            }
            publish(time > 0 ? time : -1, true);
            reschedule(currentInterval());
        }));
    }

    private static void publish(long ping, boolean record) {
        lastPing = ping;
        if (record && ping > 0) {
            history.add(ping);
            while (history.size() > MAX_HISTORY) {
                history.remove(0);
            }
        }
        for (Listener listener : new ArrayList<>(listeners)) {
            listener.onPing(ping);
        }
    }
}
