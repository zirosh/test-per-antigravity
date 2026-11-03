package app.caricasolare;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final String HOME = "file:///android_asset/index.html";
    private Api api;
    private WebView web;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean onAppPage = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.api = Api.get(this);
        Window window = getWindow();
        window.setStatusBarColor(Color.rgb(58, 68, 84));
        window.setNavigationBarColor(Color.rgb(26, 27, 30));

        WebView webView = new WebView(this);
        this.web = webView;
        webView.setBackgroundColor(Color.rgb(12, 13, 15));
        WebSettings settings = this.web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        this.web.addJavascriptInterface(new Bridge(), "Native");
        this.web.setWebChromeClient(new WebChromeClient());
        this.web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                MainActivity.this.onAppPage = url != null && url.startsWith(HOME);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (!MainActivity.this.api.isCallback(url)) {
                    return false;
                }
                MainActivity.this.finishLogin(url);
                return true;
            }
        });
        setContentView(this.web);

        Uri data = getIntent() == null ? null : getIntent().getData();
        this.web.loadUrl(HOME);
        if (data != null && this.api.isCallback(data.toString())) {
            finishLogin(data.toString());
        }
    }

    private void finishLogin(final String callbackUrl) {
        this.pool.execute(() -> {
            try {
                this.api.finishLogin(callbackUrl);
                this.main.post(() -> this.web.loadUrl(HOME + "?login=ok#settings"));
            } catch (Exception e) {
                this.main.post(() -> {
                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                    this.web.loadUrl(HOME + "#settings");
                });
            }
        });
    }

    private void askPermissions() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 7);
        } else {
            askBatteryIfNeeded();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 7) {
            askBatteryIfNeeded();
        }
    }

    private void askBatteryIfNeeded() {
        if (!getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName())) {
            askBattery();
        }
    }

    private void askBattery() {
        Uri pkgUri = Uri.parse("package:" + getPackageName());
        if (getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName())) {
            startActivity(new Intent("android.settings.APPLICATION_DETAILS_SETTINGS", pkgUri));
            return;
        }
        try {
            startActivity(new Intent("android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", pkgUri));
        } catch (Exception unused) {
            startActivity(new Intent("android.settings.APPLICATION_DETAILS_SETTINGS", pkgUri));
        }
    }

    @Override
    public void onBackPressed() {
        if (this.web.canGoBack() && (this.web.getUrl() == null || !this.web.getUrl().startsWith(HOME))) {
            this.web.loadUrl(HOME + "#settings");
        } else if (this.web.getUrl() != null && this.web.getUrl().contains("#settings")) {
            this.web.loadUrl(HOME);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        this.pool.shutdownNow();
        this.web.destroy();
        super.onDestroy();
    }

    final class Bridge {
        @JavascriptInterface
        public void call(final int id, final String method, final String rawArgs) {
            if (!MainActivity.this.onAppPage) {
                return;
            }
            MainActivity.this.pool.execute(() -> {
                String jsonResp;
                boolean ok;
                try {
                    String safeArgs = (rawArgs == null || rawArgs.isEmpty()) ? "{}" : rawArgs;
                    jsonResp = MainActivity.this.dispatch(method, new JSONObject(safeArgs));
                    ok = true;
                } catch (Exception e) {
                    String err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    if ((e instanceof UnknownHostException)
                            || (e instanceof SocketTimeoutException)
                            || (e instanceof ConnectException)) {
                        err = "Connessione a internet assente o lenta";
                    }
                    jsonResp = "{\"error\":" + JSONObject.quote(err) + "}";
                    ok = false;
                }
                final String js = "window.__native(" + id + "," + ok + "," + JSONObject.quote(jsonResp) + ")";
                MainActivity.this.main.post(() -> MainActivity.this.web.evaluateJavascript(js, null));
            });
        }
    }

    private String dispatch(String method, JSONObject args) throws Exception {
        switch (method) {
            case "auto.battery":
                this.main.post(this::askBattery);
                return "{}";
            case "auto.runNow":
                if (!AutoEngine.enabled(this)) {
                    throw new Exception("L'automatico è spento");
                }
                startForegroundService(
                        new Intent(this, AutoService.class)
                                .setAction(AutoService.ACTION_RUN)
                                .putExtra("force", args.optBoolean("force"))
                );
                return "{}";
            case "auto.status":
                JSONObject st = new AutoEngine(this).status();
                st.put("battery_ok", getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName()));
                boolean notifOk = Build.VERSION.SDK_INT < 33
                        || checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED;
                st.put("notifications_ok", notifOk);
                return st.toString();
            case "auto.set":
                if (args.optBoolean("on")) {
                    this.main.post(this::askPermissions);
                    AutoService.start(this);
                } else {
                    AutoService.stop(this);
                }
                return "{}";
            case "tesla.login":
                final String loginUrl = this.api.loginUrl();
                this.main.post(() -> this.web.loadUrl(loginUrl));
                return "{}";
            case "tesla.loginExternal":
                final String extUrl = this.api.loginUrl();
                this.main.post(() -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(extUrl))));
                return "{}";
            case "tesla.loginFinish":
                this.api.finishLogin(args.optString("url"));
                return "{}";
            case "tesla.state":
                return this.api.vehicleState().toString();
            case "tesla.wake":
                this.api.wake();
                return "{}";
            case "tesla.wakeAndRead":
                return this.api.wakeAndRead(args.optLong("timeout_ms", 45_000L)).toString();
            case "tesla.command":
                String cmdRes = this.api.command(args.optString("name"), args.optInt("value"), true).toString();
                new AutoEngine(this).manualCommand();
                return cmdRes;
            case "solax.read":
                return this.api.solax().toString();
            case "forecast":
                return this.api.forecast(args.optBoolean("force")).toString();
            case "config.get":
                return this.api.config().toString();
            case "config.save":
                this.api.save(args);
                return "{}";
            case "config.import":
                this.api.importCode(args.optString("code"));
                return "{}";
            case "config.forget":
                AutoService.stop(this);
                this.api.forget();
                return "{}";
            case "setup.status":
                return this.api.setupStatus().toString();
            case "setup.createKey":
                this.api.createKey(args.optBoolean("force"));
                return "{}";
            case "setup.domain":
                this.api.setDomain(args.optString("github"));
                return "{}";
            case "setup.checkPublished":
                return this.api.checkPublished().toString();
            case "setup.client":
                this.api.saveClient(args.optString("id"), args.optString("secret"));
                return "{}";
            case "setup.register":
                return this.api.registerDomain().toString();
            case "setup.vehicles":
                return this.api.vehicles().toString();
            case "setup.pick":
                this.api.pickVehicle(args.optString("vin"));
                return "{}";
            case "setup.paired":
                return this.api.checkPaired().toString();
            case "kv.get":
                return JSONObject.quote(this.api.kvGet(args.optString("key")));
            case "kv.set":
                this.api.kvSet(args.optString("key"), args.optString("value"));
                return "{}";
            case "samples.add":
                this.api.addSample(args.getJSONObject("sample"));
                return "{}";
            case "history":
                return this.api.history().toString();
            case "stats":
                return this.api.stats().toString();
            case "copy":
                final String copyText = args.optString("text");
                this.main.post(() -> ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                        .setPrimaryClip(ClipData.newPlainText("Carica solare", copyText)));
                return "{}";
            case "open":
                final String openUrl = args.optString("url");
                if (!openUrl.startsWith("https://")) {
                    throw new Exception("Indirizzo non valido");
                }
                this.main.post(() -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(openUrl))));
                return "{}";
            default:
                throw new Exception("Funzione sconosciuta: " + method);
        }
    }
}

