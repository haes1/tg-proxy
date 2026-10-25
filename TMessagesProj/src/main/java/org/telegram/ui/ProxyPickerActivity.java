/*
 * This is the source code of Telegram for Android v. 7.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;
import org.telegram.utils.proxy.ProxySettings;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Downloads a list of public MTProto proxies, checks them on the device and enables the fastest one.
 * The rest of the working proxies are saved to the proxy list as spares.
 */
public class ProxyPickerActivity extends BaseFragment {

    // Spare list used when PROXY_LIST_URL is unreachable or returns nothing usable.
    // Add entries as {"server", "port", "secret"}, e.g. {"1.2.3.4", "443", "ee0123...abcd"}.
    private static final String[][] FALLBACK_PROXIES = {
    };

    private static final int MAX_PROXIES = 100;
    private static final int PARALLEL_CHECKS = 12;
    private static final long CHECK_TIMEOUT_MS = 7000;
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final int CONNECT_TRIES = 5;
    private static final long CONNECT_WAIT_MS = 12000;

    private static final Pattern HOST_PATTERN = Pattern.compile("^[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$|^[0-9A-Fa-f:]{2,39}$");
    private static final Pattern HEX_PATTERN = Pattern.compile("^[0-9A-Fa-f]+$");

    private static class Candidate {
        final ProxySettings settings;
        boolean done;
        boolean responded;
        long ping;

        Candidate(ProxySettings settings) {
            this.settings = settings;
        }
    }

    private ButtonWithCounterView pickButton;
    private TextView statusTextView;

    private final ArrayList<Candidate> candidates = new ArrayList<>();
    private int nextIndex;
    private int running;
    private int checkedCount;
    private int workingCount;
    private boolean inProgress;
    private boolean destroyed;
    private int generation;

    @Override
    public boolean onFragmentCreate() {
        SharedConfig.loadProxyList();
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        destroyed = true;
        generation++;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.ProxyPickerTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        ScrollView scrollView = new ScrollView(context);
        scrollView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(24), dp(20), dp(24));
        scrollView.addView(layout, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        TextView infoTextView = new TextView(context);
        infoTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        infoTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        infoTextView.setText(getString(R.string.ProxyPickerInfo));
        layout.addView(infoTextView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        pickButton = new ButtonWithCounterView(context, getResourceProvider());
        pickButton.setText(getString(R.string.ProxyPickerButton), false);
        pickButton.setOnClickListener(v -> {
            if (inProgress) {
                cancel();
            } else {
                start();
            }
        });
        layout.addView(pickButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 20, 0, 0));

        statusTextView = new TextView(context);
        statusTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        statusTextView.setGravity(Gravity.CENTER_HORIZONTAL);
        statusTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        layout.addView(statusTextView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 16, 0, 0));

        TextView warningTextView = new TextView(context);
        warningTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        warningTextView.setGravity(Gravity.CENTER_HORIZONTAL);
        warningTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText4));
        warningTextView.setText(getString(R.string.ProxyPickerWarning));
        layout.addView(warningTextView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 24, 0, 0));

        fragmentView = scrollView;
        return fragmentView;
    }

    private void start() {
        inProgress = true;
        final int gen = ++generation;
        pickButton.setText(getString(R.string.Cancel), true);
        statusTextView.setText(getString(R.string.ProxyPickerLoading));
        Utilities.externalNetworkQueue.postRunnable(() -> {
            ArrayList<ProxySettings> list = parseProxyList(downloadList());
            if (list.isEmpty()) {
                list = loadFallbackList();
            }
            final ArrayList<ProxySettings> result = list;
            AndroidUtilities.runOnUIThread(() -> {
                if (gen == generation) {
                    startChecks(result);
                }
            });
        });
    }

    private void cancel() {
        generation++;
        finish(getString(R.string.ProxyPickerCancelled));
    }

    private void finish(CharSequence status) {
        inProgress = false;
        if (destroyed) {
            return;
        }
        pickButton.setText(getString(R.string.ProxyPickerButton), true);
        statusTextView.setText(status);
    }

    private static String downloadList() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(BuildVars.PROXY_LIST_URL).openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setInstanceFollowRedirects(true);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return null;
            }
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    if (out.size() > MAX_RESPONSE_BYTES) {
                        return null;
                    }
                }
                return out.toString("UTF-8");
            }
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static ArrayList<ProxySettings> parseProxyList(String json) {
        ArrayList<ProxySettings> result = new ArrayList<>();
        if (TextUtils.isEmpty(json)) {
            return result;
        }
        HashSet<ProxySettings> seen = new HashSet<>();
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length() && result.size() < MAX_PROXIES; i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) {
                    continue;
                }
                Object port = object.opt("port");
                ProxySettings settings = buildProxy(object.optString("server", null), port instanceof Number ? ((Number) port).intValue() : parsePort(object.optString("port", null)), object.optString("secret", null));
                if (settings != null && seen.add(settings)) {
                    result.add(settings);
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return result;
    }

    private static ArrayList<ProxySettings> loadFallbackList() {
        ArrayList<ProxySettings> result = new ArrayList<>();
        HashSet<ProxySettings> seen = new HashSet<>();
        for (String[] entry : FALLBACK_PROXIES) {
            if (entry.length < 3 || result.size() >= MAX_PROXIES) {
                continue;
            }
            ProxySettings settings = buildProxy(entry[0], parsePort(entry[1]), entry[2]);
            if (settings != null && seen.add(settings)) {
                result.add(settings);
            }
        }
        return result;
    }

    private static int parsePort(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    // Rejects anything that is not a plausible MTProto proxy: host name or IP, port 1-65535,
    // hex secret of 32 (plain), 34 (dd) or 34+ (ee + fake-TLS domain) characters.
    private static ProxySettings buildProxy(String server, int port, String secret) {
        if (server == null || secret == null) {
            return null;
        }
        server = server.trim();
        secret = secret.trim();
        if (server.isEmpty() || server.length() > 253 || !HOST_PATTERN.matcher(server).matches()) {
            return null;
        }
        if (port < 1 || port > 65535) {
            return null;
        }
        if (secret.length() < 32 || secret.length() > 600 || secret.length() % 2 != 0 || !HEX_PATTERN.matcher(secret).matches()) {
            return null;
        }
        ProxySettings settings = ProxySettings.builder()
                .setType(ProxySettings.Type.MTPROTO)
                .setAddress(server)
                .setPort(port)
                .setSecret(secret)
                .build();
        return settings.isValid() ? settings : null;
    }

    private void startChecks(ArrayList<ProxySettings> list) {
        if (destroyed) {
            return;
        }
        if (list.isEmpty()) {
            finish(getString(R.string.ProxyPickerNoList));
            return;
        }
        candidates.clear();
        for (ProxySettings settings : list) {
            candidates.add(new Candidate(settings));
        }
        nextIndex = 0;
        running = 0;
        checkedCount = 0;
        workingCount = 0;
        updateProgress();
        scheduleChecks(generation);
    }

    private void updateProgress() {
        statusTextView.setText(formatString(R.string.ProxyPickerProgress, checkedCount, candidates.size(), workingCount));
    }

    private void scheduleChecks(int gen) {
        while (gen == generation && running < PARALLEL_CHECKS && nextIndex < candidates.size()) {
            final Candidate candidate = candidates.get(nextIndex++);
            running++;
            final Runnable timeout = () -> onCheckResult(gen, candidate, -1, false);
            AndroidUtilities.runOnUIThread(timeout, CHECK_TIMEOUT_MS);
            ConnectionsManager.getInstance(currentAccount).checkProxy(candidate.settings, time -> AndroidUtilities.runOnUIThread(() -> {
                AndroidUtilities.cancelRunOnUIThread(timeout);
                onCheckResult(gen, candidate, time, true);
            }));
        }
        if (gen == generation && running == 0 && nextIndex >= candidates.size()) {
            onChecksDone(gen);
        }
    }

    private void onCheckResult(int gen, Candidate candidate, long time, boolean responded) {
        if (gen != generation || candidate.done) {
            return;
        }
        candidate.done = true;
        candidate.responded = responded;
        candidate.ping = time;
        running--;
        checkedCount++;
        if (time > 0) {
            workingCount++;
        }
        if (!destroyed) {
            updateProgress();
        }
        scheduleChecks(gen);
    }

    private void onChecksDone(int gen) {
        ArrayList<Candidate> working = new ArrayList<>();
        boolean anyResponse = false;
        for (Candidate candidate : candidates) {
            anyResponse |= candidate.responded;
            if (candidate.ping > 0) {
                working.add(candidate);
            }
        }
        if (!working.isEmpty()) {
            Collections.sort(working, (a, b) -> Long.compare(a.ping, b.ping));
            applyWorking(working);
            Candidate best = working.get(0);
            finish(formatString(R.string.ProxyPickerDone, best.settings.getAddress(), best.ping, working.size() - 1));
            return;
        }
        if (!anyResponse && !isConnected()) {
            // checkProxy needs an auth key, which a fresh install cannot get while Telegram is blocked:
            // the checks never answer. Fall back to TCP reachability and try proxies for real.
            startTcpChecks(gen);
            return;
        }
        finish(getString(R.string.ProxyPickerNothingFound));
    }

    private boolean isConnected() {
        int state = ConnectionsManager.getInstance(currentAccount).getConnectionState();
        return state == ConnectionsManager.ConnectionStateConnected || state == ConnectionsManager.ConnectionStateUpdating;
    }

    private void startTcpChecks(int gen) {
        statusTextView.setText(getString(R.string.ProxyPickerTcpCheck));
        final ArrayList<Candidate> list = new ArrayList<>(candidates);
        new Thread(() -> {
            ExecutorService executor = Executors.newFixedThreadPool(PARALLEL_CHECKS);
            for (Candidate candidate : list) {
                candidate.ping = -1;
                executor.execute(() -> {
                    if (gen != generation) {
                        return;
                    }
                    long startTime = SystemClock.elapsedRealtime();
                    try (Socket socket = new Socket()) {
                        socket.connect(new InetSocketAddress(candidate.settings.getAddress(), candidate.settings.getPort()), (int) CHECK_TIMEOUT_MS);
                        candidate.ping = Math.max(1, SystemClock.elapsedRealtime() - startTime);
                    } catch (Exception ignore) {
                    }
                });
            }
            executor.shutdown();
            try {
                executor.awaitTermination(CHECK_TIMEOUT_MS * (list.size() / PARALLEL_CHECKS + 2), java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignore) {
            }
            ArrayList<Candidate> reachable = new ArrayList<>();
            for (Candidate candidate : list) {
                if (candidate.ping > 0) {
                    reachable.add(candidate);
                }
            }
            Collections.sort(reachable, (a, b) -> Long.compare(a.ping, b.ping));
            AndroidUtilities.runOnUIThread(() -> {
                if (gen != generation) {
                    return;
                }
                if (reachable.isEmpty()) {
                    finish(getString(R.string.ProxyPickerNothingFound));
                } else {
                    tryConnect(gen, reachable, 0);
                }
            });
        }).start();
    }

    // Enables reachable proxies one by one until the app actually connects through one of them.
    private void tryConnect(int gen, ArrayList<Candidate> reachable, int index) {
        if (gen != generation) {
            return;
        }
        if (index >= reachable.size() || index >= CONNECT_TRIES) {
            disableProxy();
            finish(getString(R.string.ProxyPickerNothingFound));
            return;
        }
        Candidate candidate = reachable.get(index);
        if (!destroyed) {
            statusTextView.setText(formatString(R.string.ProxyPickerTrying, index + 1, Math.min(reachable.size(), CONNECT_TRIES)));
        }
        enableProxy(SharedConfig.addProxy(new SharedConfig.ProxyInfo(candidate.settings)));
        final long startTime = SystemClock.elapsedRealtime();
        AndroidUtilities.runOnUIThread(new Runnable() {
            @Override
            public void run() {
                if (gen != generation) {
                    return;
                }
                if (isConnected()) {
                    ArrayList<Candidate> rest = new ArrayList<>(reachable.subList(index + 1, reachable.size()));
                    // Spares here are only known to accept TCP; ProxyListActivity re-checks them later.
                    for (int i = rest.size() - 1; i >= 0; i--) {
                        SharedConfig.addProxy(new SharedConfig.ProxyInfo(rest.get(i).settings));
                    }
                    SharedConfig.saveProxyList();
                    finish(formatString(R.string.ProxyPickerDone, candidate.settings.getAddress(), candidate.ping, rest.size()));
                } else if (SystemClock.elapsedRealtime() - startTime < CONNECT_WAIT_MS) {
                    AndroidUtilities.runOnUIThread(this, 500);
                } else {
                    SharedConfig.ProxyInfo failed = findProxy(candidate.settings);
                    if (failed != null) {
                        SharedConfig.deleteProxy(failed);
                    }
                    tryConnect(gen, reachable, index + 1);
                }
            }
        }, 500);
    }

    private static SharedConfig.ProxyInfo findProxy(ProxySettings settings) {
        for (SharedConfig.ProxyInfo info : SharedConfig.proxyList) {
            if (info.settings.equals(settings)) {
                return info;
            }
        }
        return null;
    }

    private void applyWorking(ArrayList<Candidate> working) {
        long now = SystemClock.elapsedRealtime();
        SharedConfig.ProxyInfo best = null;
        // addProxy puts new entries on top, so add the slowest first.
        for (int i = working.size() - 1; i >= 0; i--) {
            Candidate candidate = working.get(i);
            SharedConfig.ProxyInfo info = SharedConfig.addProxy(new SharedConfig.ProxyInfo(candidate.settings));
            info.ping = candidate.ping;
            info.available = true;
            info.availableCheckTime = now;
            best = info;
        }
        enableProxy(best);
        SharedConfig.saveProxyList();
    }

    private static void enableProxy(SharedConfig.ProxyInfo info) {
        SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();
        info.settings.toSharedPreferences(editor);
        editor.putBoolean("proxy_enabled", true);
        editor.commit();
        SharedConfig.currentProxy = info;
        ConnectionsManager.setProxySettings(true, info.settings);
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
    }

    private static void disableProxy() {
        SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();
        editor.putBoolean("proxy_enabled", false);
        editor.commit();
        ConnectionsManager.setProxySettings(false, null);
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
    }
}
