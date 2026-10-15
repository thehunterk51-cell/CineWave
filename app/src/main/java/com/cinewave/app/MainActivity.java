package com.cinewave.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.cinewave.app.media.DownloadCoordinator;
import com.cinewave.app.media.MediaInfo;
import com.cinewave.app.media.MediaSaver;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    // ------------------------------------------------------------------ urls
    private static final String MOVIES_URL = "https://azmovies.to/";
    private static final String SERIES_URL = "https://noxx.gg/";
    private static final String UPDATE_URL =
            "https://raw.githubusercontent.com/thehunterk51-cell/CineWave/main/version.json";
    private static final String RELEASES_URL =
            "https://github.com/thehunterk51-cell/CineWave/releases/latest";
    /** Blocklist the app refreshes itself from. Same file that ships in the APK. */
    private static final String FILTERS_URL =
            "https://raw.githubusercontent.com/thehunterk51-cell/CineWave/main/app/src/main/assets/adblock_hosts.txt";

    private static final String PREFS = "cinewave_settings";
    private static final String PREF_ADBLOCK = "ad_block_enabled";
    private static final String PREF_POPUPS = "popup_block_enabled";
    private static final String ASSET_FILTERS = "adblock_hosts.txt";
    private static final String ASSET_PROBE = "media_probe.js";
    private static final String FILTERS_CACHE = "adblock_hosts_cache.txt";
    private static final long FILTERS_MAX_AGE_MS = 3L * 24 * 60 * 60 * 1000;
    private static final int APP_VERSION_CODE = 5;

    private static final int BG = 0xFF0F111D;
    private static final int DIM = 0xFF8E92A8;
    private static final int PURPLE = 0xFF673AB7;
    private static final int SLATE = 0xFF363C50;
    private static final int TEAL = 0xFF007791;

    private static final int MATCH = android.view.ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

    /** Immersive flags: hide the bars and let the video use the whole panel. */
    private static final int IMMERSIVE =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;

    /**
     * Tiny always-on seed list so that the very first requests of the very
     * first page are already filtered, before the big asset finishes loading
     * on its background thread.
     */
    private static final String[] CORE_BLOCK = {
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "adservice.google.com", "adnxs.com", "adsrvr.org", "advertising.com",
            "criteo.com", "criteo.net", "taboola.com", "outbrain.com",
            "popads.net", "popcash.net", "propellerads.com", "exoclick.com",
            "trafficjunky.net", "juicyads.com", "adsterra.com", "hilltopads.net",
            "onclickads.net", "adcash.com", "mgid.com", "revcontent.com",
            "bidvertiser.com", "monetag.com", "admaven.com", "clickadu.com",
            "evadav.com", "galaksion.com", "kadam.net", "zeropark.com",
            "profitableratecpm.com", "pushnami.com", "linkvertise.com"
    };

    /**
     * Never block these: the two catalogues the app opens, plus the shared
     * infrastructure and embed hosts whose blocking would break rendering or
     * video playback. The asset file carries the same list as {@code @@} rules.
     */
    private static final String[] CORE_ALLOW = {
            // Broad CDN apexes are deliberately absent: the allowlist matches
            // subdomains, so allowing an apex would also unblock the ad
            // servers hosted under it.
            "azmovies.to", "noxx.gg",
            "gstatic.com", "googleapis.com", "recaptcha.net",
            "hcaptcha.com", "cloudflare.com", "jsdelivr.net", "jquery.com",
            "unpkg.com", "bootstrapcdn.com", "fontawesome.com",
            "youtube.com", "youtu.be", "ytimg.com", "googlevideo.com",
            "dailymotion.com", "dmcdn.net", "vimeo.com", "vimeocdn.com",
            "ok.ru", "vk.com", "mega.nz", "streamtape.com", "dood.yt",
            "filemoon.sx", "vidsrc.to", "vidsrc.me", "vidsrc.net", "embed.su",
            "mp4upload.com", "upstream.to", "mixdrop.co", "vidoza.net",
            "vidmoly.to", "streamsb.com", "bunnycdn.com", "b-cdn.net"
    };

    private static final Pattern VALID_LABEL =
            Pattern.compile("^[a-z0-9]([a-z0-9\\-]{0,61}[a-z0-9])?$");
    private static final Pattern VALID_DOMAIN =
            Pattern.compile("^[a-z0-9]([a-z0-9\\-]{0,61}[a-z0-9])?"
                    + "(\\.[a-z0-9]([a-z0-9\\-]{0,61}[a-z0-9])?)+$");
    private static final Pattern ONLY_DOTS_AND_DIGITS = Pattern.compile("^[0-9.]+$");

    // ----------------------------------------------------------------- state
    private final AtomicInteger blockedCount = new AtomicInteger();
    private final Set<String> blockedHostsThisPage =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private final Handler ui = new Handler(Looper.getMainLooper());

    private volatile Rules rules = Rules.EMPTY;
    private volatile String cosmeticCss;
    private volatile String cosmeticJs;
    private volatile String probeJs;
    private volatile String topHost = "";
    private volatile long lastToastAt;
    private volatile boolean headingQueued;
    private volatile boolean refreshingRules;

    private LinearLayout root;
    private FrameLayout rootFrame;
    private LinearLayout header;
    private WebView webView;
    private Button adBlockButton;
    private Button popupButton;
    private TextView headingView;
    private TextView statsView;
    private Button fullscreenButton;
    private String siteTitle;
    private boolean adBlockEnabled = true;
    private boolean popupBlockEnabled = true;
    private String userAgent = "";

    // ----------------------------------------------------------- fullscreen
    private FrameLayout fullscreenHost;
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;
    private boolean chromeFullscreen;

    // -------------------------------------------------------------- download
    private static final int REQ_STORAGE = 4001;
    private Dialog progressDialog;
    private TextView progressStage;
    private TextView progressDetail;
    private ProgressBar progressBar;
    private DownloadCoordinator.Request pendingSave;
    private boolean probing;

    // ---------------------------------------------------------------- life

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        adBlockEnabled = prefs.getBoolean(PREF_ADBLOCK, true);
        popupBlockEnabled = prefs.getBoolean(PREF_POPUPS, true);
        loadRules();
        showHome();
        checkForUpdate(false);
    }

    @Override
    protected void onDestroy() {
        dismissProgress();
        destroyWebView();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_STORAGE) return;
        DownloadCoordinator.Request request = pendingSave;
        pendingSave = null;
        if (request != null) launchSave(request);
    }

    @Override
    public void onBackPressed() {
        if (progressDialog != null) {
            confirmCancelDownload();
            return;
        }
        if (customView != null || chromeFullscreen) {
            leaveFullscreen();
            return;
        }
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            showHome();
        }
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // The fullscreen view is re-measured on its own, but its layout params
        // have to be restated after a rotation or it keeps the old bounds.
        if (customView != null && fullscreenHost != null) {
            customView.setSystemUiVisibility(IMMERSIVE);
            FrameLayout.LayoutParams lp =
                    new FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER);
            fullscreenHost.removeView(customView);
            fullscreenHost.addView(customView, lp);
        }
    }

    // --------------------------------------------------------------- views

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView text(String value, int size, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private Button button(String label, int color) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color));
        b.setOnFocusChangeListener((v, f) -> {
            v.setScaleX(f ? 1.04f : 1f);
            v.setScaleY(f ? 1.04f : 1f);
            v.setElevation(f ? dp(8) : 0);
        });
        return b;
    }

    private LinearLayout newRoot() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER_HORIZONTAL);
        l.setPadding(dp(22), dp(18), dp(22), dp(18));
        l.setBackgroundColor(BG);
        l.setFocusableInTouchMode(true);
        return l;
    }

    private void showHome() {
        leaveFullscreen();
        dismissProgress();
        destroyWebView();
        rootFrame = null;
        header = null;
        headingView = null;
        siteTitle = null;
        root = newRoot();

        TextView logo = text("\uD83C\uDFAC  CineWave", 30, Color.WHITE);
        logo.setTypeface(null, 1);
        root.addView(logo, new LinearLayout.LayoutParams(-1, dp(66)));
        root.addView(text("Movies and series in one place", 17, Color.rgb(190, 195, 215)),
                new LinearLayout.LayoutParams(-1, dp(40)));

        Button movies = button("\uD83C\uDF9E  Movies  \u2022  AZMovies", Color.rgb(103, 58, 183));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, dp(58));
        bp.setMargins(0, dp(8), 0, dp(8));
        root.addView(movies, bp);
        movies.setOnClickListener(v -> openSite("Movies", MOVIES_URL));

        Button series = button("\uD83D\uDCFA  Series  \u2022  NOXX", TEAL);
        root.addView(series, new LinearLayout.LayoutParams(-1, dp(58)));
        series.setOnClickListener(v -> openSite("Series", SERIES_URL));

        adBlockButton = button("", SLATE);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, dp(54));
        ap.setMargins(0, dp(12), 0, dp(7));
        root.addView(adBlockButton, ap);
        updateAdButton();
        adBlockButton.setOnClickListener(v -> toggleAdBlock());

        popupButton = button("", SLATE);
        root.addView(popupButton, new LinearLayout.LayoutParams(-1, dp(54)));
        updatePopupButton();
        popupButton.setOnClickListener(v -> togglePopups());

        statsView = text("", 12, DIM);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, dp(34));
        sp.setMargins(0, dp(4), 0, dp(4));
        root.addView(statsView, sp);
        updateStats();

        Button update = button("Check for updates", SLATE);
        root.addView(update, new LinearLayout.LayoutParams(-1, dp(50)));
        update.setOnClickListener(v -> checkForUpdate(true));

        Button saved = button("\u2606  Watch later", SLATE);
        root.addView(saved, new LinearLayout.LayoutParams(-1, dp(50)));
        saved.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Watch later")
                .setMessage("A saved list is not available in this version.")
                .setPositiveButton("OK", null).show());

        TextView note = text("For phones, Android TV and TV boxes. Some websites may not fully "
                + "support WebView or remote navigation.", 13, Color.rgb(160, 165, 185));
        note.setPadding(dp(4), dp(10), dp(4), dp(4));
        root.addView(note, new LinearLayout.LayoutParams(-1, -2));

        setContentView(root);
        root.requestFocus();
    }

    private void updateAdButton() {
        if (adBlockButton != null) {
            adBlockButton.setText(adBlockEnabled ? "Ad and tracker blocking: ON \u2713"
                    : "Ad and tracker blocking: OFF");
        }
    }

    private void updatePopupButton() {
        if (popupButton != null) {
            popupButton.setText(popupBlockEnabled ? "Popup and redirect blocking: ON \u2713"
                    : "Popup and redirect blocking: OFF");
        }
    }

    private void updateStats() {
        if (statsView == null) return;
        Rules r = rules;
        statsView.setText("Blocked since launch: " + blockedCount.get()
                + "   \u2022   rules loaded: " + r.total()
                + (r.complete ? "" : " (loading\u2026)"));
    }

    private void toggleAdBlock() {
        adBlockEnabled = !adBlockEnabled;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(PREF_ADBLOCK, adBlockEnabled).apply();
        updateAdButton();
        Toast.makeText(this, adBlockEnabled ? "Ad blocking enabled" : "Ad blocking disabled",
                Toast.LENGTH_SHORT).show();
        if (webView != null) webView.reload();
    }

    private void togglePopups() {
        popupBlockEnabled = !popupBlockEnabled;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(PREF_POPUPS, popupBlockEnabled).apply();
        updatePopupButton();
        Toast.makeText(this, popupBlockEnabled ? "Popup blocking enabled" : "Popup blocking disabled",
                Toast.LENGTH_SHORT).show();
        if (webView != null) webView.reload();
    }

    // ----------------------------------------------------------- web screen

    private void openSite(String title, String url) {
        dismissProgress();
        leaveFullscreen();
        destroyWebView();
        siteTitle = title;
        blockedHostsThisPage.clear();
        topHost = hostOf(url);

        rootFrame = new FrameLayout(this);
        rootFrame.setBackgroundColor(BG);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.addView(buildHeader(title), new LinearLayout.LayoutParams(MATCH, WRAP));

        webView = buildWebView();
        column.addView(webView, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        rootFrame.addView(column, new FrameLayout.LayoutParams(MATCH, MATCH));
        setContentView(rootFrame);
        webView.loadUrl(url);
        webView.requestFocus();
    }

    private LinearLayout buildHeader(String title) {
        header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(6), dp(4), dp(6), dp(4));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);

        Button back = button("Back", SLATE);
        top.addView(back, new LinearLayout.LayoutParams(dp(78), dp(46)));
        back.setOnClickListener(v -> showHome());

        headingView = text(title, 16, Color.WHITE);
        headingView.setTypeface(null, 1);
        top.addView(headingView, new LinearLayout.LayoutParams(0, dp(46), 1f));

        Button options = button("Options", SLATE);
        top.addView(options, new LinearLayout.LayoutParams(dp(92), dp(46)));
        options.setOnClickListener(v -> showOptions());
        header.addView(top, new LinearLayout.LayoutParams(MATCH, dp(48)));

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);

        fullscreenButton = button("Fullscreen", TEAL);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        fp.setMargins(0, 0, dp(6), 0);
        actions.addView(fullscreenButton, fp);
        fullscreenButton.setOnClickListener(v -> toggleFullscreen());

        Button save = button("Save video", PURPLE);
        LinearLayout.LayoutParams sp2 = new LinearLayout.LayoutParams(0, dp(46), 1f);
        sp2.setMargins(dp(6), 0, 0, 0);
        actions.addView(save, sp2);
        save.setOnClickListener(v -> offerSave());

        header.addView(actions, new LinearLayout.LayoutParams(MATCH, dp(48)));
        return header;
    }

    private void showOptions() {
        String[] labels = {
                adBlockEnabled ? "Ad and tracker blocking: ON \u2713"
                        : "Ad and tracker blocking: OFF",
                popupBlockEnabled ? "Popup and redirect blocking: ON \u2713"
                        : "Popup and redirect blocking: OFF"
        };
        new AlertDialog.Builder(this)
                .setTitle("Blocking")
                .setItems(labels, (d, which) -> {
                    if (which == 0) toggleAdBlock();
                    else togglePopups();
                    showOptions();
                })
                .setPositiveButton("Close", null)
                .show();
    }

    // ---------------------------------------------------------- fullscreen

    private boolean isFullscreen() {
        return customView != null || chromeFullscreen;
    }

    /**
     * Asks the player to go fullscreen and, if nothing answers, falls back to
     * filling the panel ourselves. Either way the user ends up watching a big
     * picture rather than a letterboxed strip.
     */
    private void toggleFullscreen() {
        if (isFullscreen()) {
            leaveFullscreen();
            return;
        }
        if (webView == null) return;
        webView.evaluateJavascript(
                "(function(){var v=document.querySelector('video');if(!v)return 'none';"
                        + "var f=v.requestFullscreen||v.webkitRequestFullscreen"
                        + "||v.webkitEnterFullscreen||v.mozRequestFullScreen||v.msRequestFullscreen;"
                        + "if(!f)return 'none';try{f.call(v);return 'ok';}"
                        + "catch(e){return 'failed';}})()",
                value -> {
                    String answer = unquote(value);
                    if ("ok".equals(answer)) return;
                    if ("none".equals(answer)) {
                        Toast.makeText(this, "No video found on this page, so only the page "
                                + "is going fullscreen.", Toast.LENGTH_SHORT).show();
                    }
                    enterChromeFullscreen();
                });
    }

    private void enterChromeFullscreen() {
        if (chromeFullscreen) return;
        chromeFullscreen = true;
        if (header != null) header.setVisibility(View.GONE);
        applyImmersive(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        tellGuardFullscreen(true);
        updateFullscreenButton();
    }

    private void leaveFullscreen() {
        WebChromeClient.CustomViewCallback callback = customCallback;
        customCallback = null;
        customView = null;
        if (fullscreenHost != null) {
            fullscreenHost.removeAllViews();
            fullscreenHost.setVisibility(View.GONE);
        }
        chromeFullscreen = false;
        if (header != null) header.setVisibility(View.VISIBLE);
        applyImmersive(false);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        tellGuardFullscreen(false);
        updateFullscreenButton();
        if (callback != null) callback.onCustomViewHidden();
    }

    private void updateFullscreenButton() {
        if (fullscreenButton != null) {
            fullscreenButton.setText(isFullscreen() ? "Exit" : "Fullscreen");
        }
    }

    private void applyImmersive(boolean on) {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(on ? IMMERSIVE : View.SYSTEM_UI_FLAG_VISIBLE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                if (on) {
                    getWindow().setDecorFitsSystemWindows(false);
                    controller.hide(WindowInsets.Type.statusBars()
                            | WindowInsets.Type.navigationBars());
                    controller.setSystemBarsBehavior(
                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                } else {
                    controller.show(WindowInsets.Type.statusBars()
                            | WindowInsets.Type.navigationBars());
                    getWindow().setDecorFitsSystemWindows(true);
                }
            }
        }
    }

    /**
     * Tells the in-page ad guard to stand down. Its fullscreen layer looks just
     * like an ad backdrop, so it has to know when the video is on screen.
     */
    private void tellGuardFullscreen(boolean on) {
        if (webView == null) return;
        webView.evaluateJavascript(
                "window.__cinewave&&window.__cinewave.setFullscreen(" + on + ");", null);
    }

    // ----------------------------------------------------------- web screen

    private WebView buildWebView() {
        WebView view = new WebView(this);
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
        view.setBackgroundColor(BG);

        WebSettings ws = view.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        ws.setLoadsImagesAutomatically(true);
        ws.setBuiltInZoomControls(false);
        ws.setDisplayZoomControls(false);

        // Popups are handled by us and dropped, so the WebView must be told to
        // ask (supportMultipleWindows=true) instead of loading window.open()
        // targets into the page we are already showing.
        ws.setSupportMultipleWindows(true);
        ws.setJavaScriptCanOpenWindowsAutomatically(false);

        // Hardening: the app never needs local or mixed-origin file access.
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        ws.setAllowFileAccessFromFileURLs(false);
        ws.setAllowUniversalAccessFromFileURLs(false);
        ws.setGeolocationEnabled(false);
        ws.setSaveFormData(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ws.setSafeBrowsingEnabled(true);
        }

        // Sites serve an "open in the app" wall when they see the WebView
        // marker, which is what triggers most of the forced-redirect ads.
        String ua = ws.getUserAgentString();
        if (ua != null && ua.contains("; wv")) {
            ua = ua.replace("; wv", "");
            ws.setUserAgentString(ua);
        }
        userAgent = ws.getUserAgentString();

        try {
            CookieManager.getInstance().setAcceptThirdPartyCookies(view, false);
        } catch (Throwable ignored) {
        }

        view.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                return handleNavigation(req.getUrl(), req.hasGesture(), req.isForMainFrame());
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                return handleNavigation(Uri.parse(url), true, true);
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req) {
                return intercept(req);
            }

            @Override
            public void onPageStarted(WebView v, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(v, url, favicon);
                blockedHostsThisPage.clear();
                topHost = hostOf(url);
                injectGuards(v);
                refreshHeading();
            }

            @Override
            public void onPageCommitVisible(WebView v, String url) {
                super.onPageCommitVisible(v, url);
                injectGuards(v);
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                super.onPageFinished(v, url);
                injectGuards(v);
                refreshHeading();
            }
        });

        view.setWebChromeClient(new WebChromeClient() {
            /**
             * The player hands us the view it wants to fill the screen with.
             * Without this the fullscreen button looks dead: the page calls
             * requestFullscreen, the WebView asks us for a surface and the
             * default client quietly does nothing.
             */
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                showCustomView(view, callback);
            }

            @Override
            public void onHideCustomView() {
                leaveFullscreen();
            }

            /**
             * A player that opens a window instead of going fullscreen leaves
             * the message unanswered, which hangs its own script. A throwaway
             * WebView is handed over so the request completes, then discarded.
             */
            @Override
            public boolean onCreateWindow(WebView v, boolean isDialog, boolean isUserGesture,
                                          Message resultMsg) {
                if (popupBlockEnabled) countBlock("Blocked a popup window");

                final WebView popup = new WebView(MainActivity.this);
                final java.util.concurrent.atomic.AtomicBoolean discarded =
                        new java.util.concurrent.atomic.AtomicBoolean(false);

                popup.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView source, WebResourceRequest req) {
                        Uri target = req == null ? null : req.getUrl();
                        if (discarded.compareAndSet(false, true)) {
                            final Uri url = target;
                            ui.post(() -> {
                                if (!popupBlockEnabled && url != null && webView != null) {
                                    webView.loadUrl(url.toString());
                                }
                                popup.destroy();
                            });
                        }
                        return true;
                    }
                });

                // A popup that never navigates would otherwise linger forever.
                ui.postDelayed(() -> {
                    if (discarded.compareAndSet(false, true)) popup.destroy();
                }, 15000);

                try {
                    ((WebView.WebViewTransport) resultMsg.obj).setWebView(popup);
                    resultMsg.sendToTarget();
                } catch (Throwable ignored) {
                    // Nothing to recover: the request simply goes unanswered.
                }
                return true;
            }

            @Override
            public boolean onJsAlert(WebView v, String url, String message, JsResult result) {
                result.cancel();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView v, String url, String message, JsResult result) {
                result.cancel();
                return true;
            }

            @Override
            public boolean onJsPrompt(WebView v, String url, String message, String defaultValue,
                                      JsPromptResult result) {
                result.cancel();
                return true;
            }
        });

        return view;
    }

    private void showCustomView(View view, WebChromeClient.CustomViewCallback callback) {
        if (customView != null || rootFrame == null) {
            callback.onCustomViewHidden();
            return;
        }
        customView = view;
        customCallback = callback;

        if (fullscreenHost == null) {
            fullscreenHost = new FrameLayout(this);
            fullscreenHost.setBackgroundColor(Color.BLACK);
            fullscreenHost.setVisibility(View.GONE);
            rootFrame.addView(fullscreenHost, new FrameLayout.LayoutParams(MATCH, MATCH));
        }
        fullscreenHost.removeAllViews();
        view.setSystemUiVisibility(IMMERSIVE);
        fullscreenHost.addView(view,
                new FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER));
        fullscreenHost.setVisibility(View.VISIBLE);

        if (header != null) header.setVisibility(View.GONE);
        applyImmersive(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        tellGuardFullscreen(true);
        updateFullscreenButton();
    }

    // -------------------------------------------------------------- blocking

    /** True when the navigation or subresource request must be dropped. */
    private boolean handleNavigation(Uri uri, boolean hasGesture, boolean mainFrame) {
        if (uri == null) return false;
        String scheme = uri.getScheme();
        if (scheme == null) return false;

        if (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")) {
            // intent://, market://, tel:, mailto:, file: ... never legitimate
            // here, and a favourite way for ad frames to escape the app.
            countBlock("Blocked an external app launch");
            return true;
        }

        String host = hostOf(uri);
        if (adBlockEnabled && rules.isBlockedHost(host)) {
            countBlock("Blocked ad site: " + host);
            return true;
        }

        if (popupBlockEnabled && mainFrame && !hasGesture && !host.isEmpty()
                && !sameSite(host, topHost) && !rules.isAllowed(host)) {
            // An automatic cross-site redirect that no one asked for: the
            // classic popunder / forced-redirect pattern.
            countBlock("Blocked an automatic redirect");
            return true;
        }

        return false;
    }

    private WebResourceResponse intercept(WebResourceRequest req) {
        if (!adBlockEnabled) return null;
        Uri uri = req.getUrl();
        if (uri == null) return null;
        String scheme = uri.getScheme();
        if (scheme == null
                || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            return null;
        }
        String host = hostOf(uri);
        if (!rules.isBlockedHost(host)) return null;

        if (blockedHostsThisPage.size() < 200) blockedHostsThisPage.add(host);
        countBlock(null);

        if (req.isForMainFrame()) {
            return htmlResponse("<!doctype html><html><head><meta name=\"viewport\" "
                    + "content=\"width=device-width,initial-scale=1\"><style>"
                    + "body{background:#0f111d;color:#e9ecf5;font-family:sans-serif;"
                    + "display:flex;align-items:center;justify-content:center;height:100vh;"
                    + "margin:0;padding:24px;text-align:center}</style></head><body><div>"
                    + "<h2>Blocked</h2><p>" + host + " is an advertising or redirect "
                    + "server and was blocked by CineWave.</p></div></body></html>");
        }
        return new WebResourceResponse("text/plain", "utf-8",
                new ByteArrayInputStream(new byte[0]));
    }

    private WebResourceResponse htmlResponse(String html) {
        return new WebResourceResponse("text/html", "utf-8",
                new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
    }

    private void injectGuards(WebView view) {
        if (view == null) return;
        if (!adBlockEnabled && !popupBlockEnabled) return;
        String css = cosmeticCss;
        String js = cosmeticJs;
        if (css == null || js == null) return;

        StringBuilder cfg = new StringBuilder();
        cfg.append("window.__cinewaveCfg={ads:").append(adBlockEnabled)
                .append(",popups:").append(popupBlockEnabled).append("};");
        cfg.append("window.__cinewaveCssText=").append(JSONObject.quote(css)).append(';');
        view.evaluateJavascript(cfg.toString(), null);
        view.evaluateJavascript(js, null);

        String hosts = blockedHostsJson();
        if (!hosts.isEmpty()) {
            view.evaluateJavascript(
                    "window.__cinewave&&window.__cinewave.setBlockedHosts(" + hosts + ");", null);
        }
        if (isFullscreen()) {
            view.evaluateJavascript(
                    "window.__cinewave&&window.__cinewave.setFullscreen(true);", null);
        }
    }

    private String blockedHostsJson() {
        if (blockedHostsThisPage.isEmpty()) return "";
        JSONArray arr = new JSONArray();
        int n = 0;
        for (String h : blockedHostsThisPage) {
            arr.put(h);
            if (++n >= 120) break;
        }
        return arr.toString();
    }

    private void countBlock(String toastMessage) {
        blockedCount.incrementAndGet();
        if (toastMessage != null) {
            long now = System.currentTimeMillis();
            if (now - lastToastAt > 2500) {
                lastToastAt = now;
                final String msg = toastMessage;
                ui.post(() -> {
                    if (!isFinishing()) {
                        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }
        if (!headingQueued) {
            headingQueued = true;
            ui.postDelayed(this::refreshHeading, 700);
        }
    }

    private void refreshHeading() {
        headingQueued = false;
        TextView hv = headingView;
        String title = siteTitle;
        if (hv == null || title == null) return;
        int n = blockedCount.get();
        hv.setText(n > 0 ? title + "  \u2022  " + n + " blocked" : title);
        updateStats();
    }

    // ------------------------------------------------------- save a movie

    private void offerSave() {
        if (webView == null) return;
        if (DownloadCoordinator.isRunning()) {
            Toast.makeText(this, "A download is already running.", Toast.LENGTH_SHORT).show();
            return;
        }
        String js = probeJs;
        if (js == null) {
            Toast.makeText(this, "Still starting up. Try again in a moment.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (probing) return;
        probing = true;
        Toast.makeText(this, "Looking for the video\u2026", Toast.LENGTH_SHORT).show();
        webView.evaluateJavascript(js, this::onProbeResult);
    }

    private void onProbeResult(String value) {
        probing = false;
        String json = unquote(value);
        if (json == null || json.isEmpty()) {
            Toast.makeText(this, "No video was found on this page. Start the film first, then "
                    + "tap Save video again.", Toast.LENGTH_LONG).show();
            offerManualLink();
            return;
        }
        MediaInfo info;
        try {
            info = MediaInfo.from(json);
        } catch (Exception e) {
            Toast.makeText(this, "Could not read the page. Try again, or paste a link.",
                    Toast.LENGTH_LONG).show();
            offerManualLink();
            return;
        }
        if (info.streams.isEmpty()) {
            Toast.makeText(this, "No video stream was found on this page. Start the film first, "
                    + "then tap Save video again.", Toast.LENGTH_LONG).show();
            offerManualLink();
            return;
        }
        chooseStream(info);
    }

    private void chooseStream(final MediaInfo info) {
        final List<MediaInfo.Stream> streams = info.streams;
        String[] labels = new String[streams.size() + 1];
        for (int i = 0; i < streams.size(); i++) {
            MediaInfo.Stream s = streams.get(i);
            String label = s.label == null || s.label.isEmpty()
                    ? "Stream " + (i + 1) : s.label;
            labels[i] = label + (s.isAdaptive() ? "  (adaptive \u2014 cannot be saved)" : "");
        }
        labels[streams.size()] = "Paste a video link\u2026";

        new AlertDialog.Builder(this)
                .setTitle("Which stream do you want?")
                .setItems(labels, (d, which) -> {
                    if (which == streams.size()) {
                        askForManualLink(info);
                        return;
                    }
                    MediaInfo.Stream chosen = streams.get(which);
                    if (chosen.isAdaptive()) {
                        new AlertDialog.Builder(this)
                                .setTitle("Adaptive stream")
                                .setMessage("This player is streaming in HLS, which is a list of "
                                        + "chunks rather than one file. CineWave cannot save it as "
                                        + "a single video.\n\nPick another entry, or paste a "
                                        + "direct .mp4 link.")
                                .setPositiveButton("OK", null)
                                .setNeutralButton("Paste a link", (x, y) -> askForManualLink(info))
                                .show();
                        return;
                    }
                    chooseSubtitle(info, chosen.url, chosen.label);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void askForManualLink(final MediaInfo info) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("https://\u2026/video.mp4");
        int pad = dp(18);
        input.setPadding(pad, pad / 2, pad, pad / 2);
        new AlertDialog.Builder(this)
                .setTitle("Video link")
                .setView(input)
                .setPositiveButton("Continue", (d, w) -> {
                    String url = input.getText().toString().trim();
                    if (url.isEmpty()) return;
                    if (!url.startsWith("http")) {
                        url = "https://" + url;
                    }
                    chooseSubtitle(info, url, "Manual link");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void offerManualLink() {
        askForManualLink(new MediaInfo());
    }

    private void chooseSubtitle(final MediaInfo info, final String videoUrl, final String label) {
        final List<MediaInfo.Sub> subs = info.subs;
        String[] items = new String[subs.size() + 1];
        items[0] = "No subtitle";
        for (int i = 0; i < subs.size(); i++) {
            MediaInfo.Sub s = subs.get(i);
            items[i + 1] = (s.label == null || s.label.isEmpty()
                    ? "Subtitle " + (i + 1) : s.label) + (s.embedded ? "  (ready to merge)" : "");
        }

        new AlertDialog.Builder(this)
                .setTitle("Add a subtitle?")
                .setItems(items, (d, which) -> {
                    MediaInfo.Sub chosen = which == 0 ? null : subs.get(which - 1);
                    startSave(videoUrl, label, chosen, info.pageUrl);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void startSave(String videoUrl, String label, MediaInfo.Sub sub, String pageUrl) {
        DownloadCoordinator.Request request = new DownloadCoordinator.Request();
        request.videoUrl = videoUrl;
        request.referer = pageUrl != null && !pageUrl.isEmpty()
                ? pageUrl : currentUrl();
        request.userAgent = userAgent;
        request.fileName = titleFor(label, pageUrl);
        request.subtitle = sub;

        // Before Android 10 the finished file is written to the shared Movies
        // folder, which needs the old storage permission. Refusing it is not
        // fatal: the save still lands in the app folder and the path is shown.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && !MediaSaver.canWritePublic(this)) {
            pendingSave = request;
            requestPermissions(new String[]{
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            return;
        }
        launchSave(request);
    }

    private void launchSave(DownloadCoordinator.Request request) {
        showProgress();
        DownloadCoordinator.start(this, request, new DownloadCoordinator.Ui() {
            @Override
            public void onStage(String stage) {
                if (progressStage != null) progressStage.setText(stage);
                if (progressDetail != null && progressBar != null) {
                    progressBar.setIndeterminate(true);
                    progressDetail.setText("Please wait\u2026");
                }
            }

            @Override
            public void onProgress(int percent) {
                if (progressBar == null) return;
                if (percent < 0) {
                    progressBar.setIndeterminate(true);
                } else {
                    progressBar.setIndeterminate(false);
                    progressBar.setProgress(percent);
                }
                if (progressDetail != null) {
                    progressDetail.setText(percent < 0 ? "Downloading\u2026"
                            : percent + "%");
                }
            }

            @Override
            public void onDone(DownloadCoordinator.Result result) {
                dismissProgress();
                showSaved(result);
            }

            @Override
            public void onFailed(String message) {
                dismissProgress();
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Could not save")
                        .setMessage(message)
                        .setPositiveButton("OK", null)
                        .show();
            }

            @Override
            public void onCancelled() {
                dismissProgress();
                Toast.makeText(MainActivity.this, "Download cancelled.",
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showSaved(final DownloadCoordinator.Result result) {
        StringBuilder message = new StringBuilder();
        message.append(result.videoName);
        if (result.subtitleName != null && !result.subtitleName.isEmpty()) {
            message.append("\n\nSubtitle: ").append(result.subtitleName);
            message.append(result.merged
                    ? "\nThe subtitle is now built into the video file."
                    : "\nSaved as a separate file.");
        }
        if (result.localPath != null && !result.localPath.isEmpty()) {
            message.append("\n\nThe gallery would not accept the file, so it is here:\n")
                    .append(result.localPath);
        }
        if (result.note != null && !result.note.isEmpty()) {
            message.append("\n\n").append(result.note);
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("Saved")
                .setMessage(message.toString())
                .setNegativeButton("Close", null);

        Uri uri = result.videoUri;
        if (uri != null && "content".equals(uri.getScheme())) {
            builder.setPositiveButton("Open", (d, w) -> {
                try {
                    startActivity(MediaSaver.viewIntent(uri));
                } catch (Exception e) {
                    Toast.makeText(this, "No app on this device can play the file.",
                            Toast.LENGTH_LONG).show();
                }
            });
        }
        builder.show();
    }

    private String titleFor(String label, String pageUrl) {
        if (label != null && !label.trim().isEmpty()) return label.trim();
        if (pageUrl != null && !pageUrl.isEmpty()) {
            MediaInfo info = new MediaInfo();
            info.pageUrl = pageUrl;
            return info.suggestedName();
        }
        return "video";
    }

    private String currentUrl() {
        if (webView == null) return "";
        String url = webView.getUrl();
        return url == null ? "" : url;
    }

    private void showProgress() {
        dismissProgress();

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(20), dp(22), dp(16));

        /* The activity theme has no title bar, so the heading lives in the
         * layout rather than on the dialog itself. */
        TextView heading = text("Saving", 19, Color.WHITE);
        heading.setTypeface(null, 1);
        box.addView(heading, new LinearLayout.LayoutParams(MATCH, WRAP));

        progressStage = text("Starting\u2026", 15, Color.rgb(190, 195, 215));
        box.addView(progressStage, new LinearLayout.LayoutParams(MATCH, WRAP));

        progressBar = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(MATCH, WRAP);
        barLp.setMargins(0, dp(14), 0, dp(6));
        box.addView(progressBar, barLp);
        progressBar.setIndeterminate(true);
        progressBar.setMax(100);

        progressDetail = text("Please wait\u2026", 14, DIM);
        box.addView(progressDetail, new LinearLayout.LayoutParams(MATCH, WRAP));

        Button cancel = button("Cancel", SLATE);
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(MATCH, dp(48));
        cancelLp.setMargins(0, dp(16), 0, 0);
        box.addView(cancel, cancelLp);
        cancel.setOnClickListener(v -> confirmCancelDownload());

        Dialog dialog = new Dialog(this);
        dialog.setContentView(box);
        dialog.setCancelable(false);
        dialog.show();
        progressDialog = dialog;
    }

    private void confirmCancelDownload() {
        new AlertDialog.Builder(this)
                .setTitle("Cancel the download?")
                .setMessage("The file being downloaded will be deleted.")
                .setPositiveButton("Keep going", null)
                .setNegativeButton("Cancel download", (d, w) -> {
                    DownloadCoordinator.cancel();
                    dismissProgress();
                })
                .show();
    }

    private void dismissProgress() {
        Dialog dialog = progressDialog;
        progressDialog = null;
        progressStage = null;
        progressDetail = null;
        progressBar = null;
        if (dialog != null && dialog.isShowing()) {
            try {
                dialog.dismiss();
            } catch (Exception ignored) {
                // The window is already gone.
            }
        }
    }

    /** evaluateJavascript hands back a JSON encoded string; undo one layer. */
    private static String unquote(String value) {
        if (value == null) return null;
        String text = value.trim();
        if (text.isEmpty() || "null".equals(text)) return null;
        try {
            JSONArray wrapped = new JSONArray("[" + text + "]");
            if (wrapped.isNull(0)) return null;
            return wrapped.optString(0, null);
        } catch (Exception e) {
            return text;
        }
    }

    // ----------------------------------------------------------------- rules

    private static final class Rules {
        static final Rules EMPTY = new Rules(Collections.<String>emptySet(),
                Collections.<String>emptySet(), Collections.<String>emptySet(), false);

        final Set<String> blocked;
        final Set<String> allowed;
        final Set<String> labels;
        final boolean complete;

        Rules(Set<String> blocked, Set<String> allowed, Set<String> labels) {
            this(blocked, allowed, labels, true);
        }

        Rules(Set<String> blocked, Set<String> allowed, Set<String> labels, boolean complete) {
            this.blocked = blocked;
            this.allowed = allowed;
            this.labels = labels;
            this.complete = complete;
        }

        int total() {
            return blocked.size() + labels.size();
        }

        boolean isAllowed(String host) {
            return host != null && !host.isEmpty() && suffixMatch(allowed, host);
        }

        boolean isBlockedHost(String host) {
            if (host == null || host.isEmpty()) return false;
            if (suffixMatch(allowed, host)) return false;
            if (suffixMatch(blocked, host)) return true;
            return labelMatch(labels, host);
        }

        /** Matches the host itself or any parent domain down to two labels. */
        private static boolean suffixMatch(Set<String> set, String host) {
            String h = host;
            while (true) {
                if (set.contains(h)) return true;
                int dot = h.indexOf('.');
                if (dot < 0) return false;
                h = h.substring(dot + 1);
                if (h.indexOf('.') < 0) return false;
            }
        }

        /** Matches a single DNS label, for ad brands that rotate their TLD. */
        private static boolean labelMatch(Set<String> set, String host) {
            if (set.isEmpty()) return false;
            int start = 0;
            int n = host.length();
            for (int i = 0; i <= n; i++) {
                if (i == n || host.charAt(i) == '.') {
                    if (i > start && set.contains(host.substring(start, i))) return true;
                    start = i + 1;
                }
            }
            return false;
        }
    }

    private void loadRules() {
        Thread t = new Thread(() -> {
            cosmeticCss = readAsset("adblock_cosmetic.css");
            cosmeticJs = readAsset("adblock_cosmetic.js");
            probeJs = readAsset(ASSET_PROBE);
            mergeRules();
            ui.post(this::updateStats);
            refreshRulesIfStale();
        }, "cinewave-rules");
        t.setDaemon(true);
        t.start();
    }

    private void mergeRules() {
        Set<String> blocked = new HashSet<>(Arrays.asList(CORE_BLOCK));
        Set<String> allowed = new HashSet<>(Arrays.asList(CORE_ALLOW));
        Set<String> labels = new HashSet<>();

        try (Reader r = new InputStreamReader(getAssets().open(ASSET_FILTERS),
                StandardCharsets.UTF_8)) {
            parseRules(new BufferedReader(r), blocked, allowed, labels);
        } catch (Exception ignored) {
        }

        File cache = new File(getFilesDir(), FILTERS_CACHE);
        if (cache.isFile()) {
            try (Reader r = new InputStreamReader(new FileInputStream(cache),
                    StandardCharsets.UTF_8)) {
                parseRules(new BufferedReader(r), blocked, allowed, labels);
            } catch (Exception ignored) {
            }
        }

        rules = new Rules(blocked, allowed, labels);
        ui.post(this::updateStats);
    }

    /** Re-downloads the blocklist from the repository when the cache is old. */
    private void refreshRulesIfStale() {
        if (refreshingRules) return;
        File cache = new File(getFilesDir(), FILTERS_CACHE);
        if (cache.isFile() && System.currentTimeMillis() - cache.lastModified()
                < FILTERS_MAX_AGE_MS) {
            return;
        }
        refreshingRules = true;
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(FILTERS_URL).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(20000);
            c.setRequestProperty("Cache-Control", "no-cache");
            c.setRequestProperty("User-Agent", "CineWave/" + APP_VERSION_CODE);
            try {
                if (c.getResponseCode() != HttpURLConnection.HTTP_OK) return;
                StringBuilder body = new StringBuilder();
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null && body.length() < 4_000_000) {
                        body.append(line).append('\n');
                    }
                }
                // Refuse stubs and truncated transfers: a short list is worse
                // than the one already bundled in the APK.
                if (body.length() < 50_000) return;
                File tmp = new File(getFilesDir(), FILTERS_CACHE + ".tmp");
                try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp),
                        StandardCharsets.UTF_8)) {
                    w.write(body.toString());
                }
                if (cache.exists() && !cache.delete()) {
                    tmp.delete();
                    return;
                }
                if (tmp.renameTo(cache)) mergeRules();
                else tmp.delete();
            } finally {
                c.disconnect();
            }
        } catch (Exception ignored) {
        } finally {
            refreshingRules = false;
        }
    }

    private void parseRules(BufferedReader reader, Set<String> blocked, Set<String> allowed,
                            Set<String> labels) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            String s = line.trim();
            if (s.isEmpty() || s.charAt(0) == '#' || s.charAt(0) == '!'
                    || s.charAt(0) == '[' || s.startsWith("//")) {
                continue;
            }
            if (s.startsWith("@@")) {
                String host = normalizeDomain(s.substring(2));
                if (host != null) allowed.add(host);
                continue;
            }
            if (s.startsWith("*.")) {
                String label = s.substring(2).trim().toLowerCase(Locale.ROOT);
                if (VALID_LABEL.matcher(label).matches()) labels.add(label);
                continue;
            }
            String host = normalizeDomain(s);
            if (host != null) blocked.add(host);
        }
    }

    /**
     * Accepts plain domains, hosts-file lines and Adblock-Plus style lines so
     * that almost any public list can be dropped into the asset file.
     */
    private static String normalizeDomain(String raw) {
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return null;
        if (s.startsWith("||")) s = s.substring(2);

        if (s.startsWith("0.0.0.0") || s.startsWith("127.0.0.1") || s.startsWith("::1")) {
            String[] parts = s.split("\\s+");
            if (parts.length < 2) return null;
            s = parts[1];
        } else if (s.indexOf(' ') > 0 || s.indexOf('\t') > 0) {
            s = s.split("\\s+")[0];
        }

        int cut = s.length();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '^' || ch == '/' || ch == '$' || ch == ':' || ch == '?'
                    || ch == '*' || ch == ',' || ch == '=') {
                cut = i;
                break;
            }
        }
        s = s.substring(0, cut);
        while (s.startsWith(".")) s = s.substring(1);
        while (s.endsWith(".")) s = s.substring(0, s.length() - 1);

        if (s.isEmpty() || s.length() > 190) return null;
        if (!s.contains(".")) return null;
        if (ONLY_DOTS_AND_DIGITS.matcher(s).matches()) return null;
        if (!VALID_DOMAIN.matcher(s).matches()) return null;
        return s;
    }

    private String readAsset(String name) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                getAssets().open(name), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    // ----------------------------------------------------------------- utils

    private static String hostOf(String url) {
        if (url == null) return "";
        try {
            String host = Uri.parse(url).getHost();
            if (host == null) return "";
            host = host.toLowerCase(Locale.ROOT);
            return host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
        } catch (Exception e) {
            return "";
        }
    }

    private static String hostOf(Uri uri) {
        if (uri == null) return "";
        String host = uri.getHost();
        if (host == null) return "";
        return host.toLowerCase(Locale.ROOT);
    }

    /** Cheap registrable-domain comparison, good enough for redirect checks. */
    private static boolean sameSite(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return false;
        return a.equals(b) || a.endsWith("." + b) || b.endsWith("." + a);
    }

    private void destroyWebView() {
        if (webView != null) {
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.destroy();
            webView = null;
        }
        customView = null;
        customCallback = null;
        if (fullscreenHost != null) {
            fullscreenHost.removeAllViews();
            fullscreenHost = null;
        }
    }

    // ------------------------------------------------------------- updating

    private void checkForUpdate(boolean manual) {
        Thread t = new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(UPDATE_URL).openConnection();
                c.setConnectTimeout(5000);
                c.setReadTimeout(5000);
                c.setRequestProperty("Cache-Control", "no-cache");
                BufferedReader r = new BufferedReader(
                        new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder body = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) body.append(line);
                r.close();
                c.disconnect();
                JSONObject json = new JSONObject(body.toString());
                int code = json.optInt("versionCode", 0);
                String name = json.optString("versionName", "new version");
                String link = json.optString("apkUrl", RELEASES_URL);
                runOnUiThread(() -> {
                    if (code > APP_VERSION_CODE) {
                        new AlertDialog.Builder(this).setTitle("Update available")
                                .setMessage("CineWave " + name
                                        + " is available. Open the release page to download it?")
                                .setNegativeButton("Later", null)
                                .setPositiveButton("Open download", (d, w) -> {
                                    try {
                                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link)));
                                    } catch (Exception e) {
                                        Toast.makeText(this, "Could not open download page.",
                                                Toast.LENGTH_LONG).show();
                                    }
                                }).show();
                    } else if (manual) {
                        Toast.makeText(this, "CineWave is up to date.", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                if (manual) {
                    runOnUiThread(() -> Toast.makeText(this, "Update check failed. Try again later.",
                            Toast.LENGTH_LONG).show());
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }
}