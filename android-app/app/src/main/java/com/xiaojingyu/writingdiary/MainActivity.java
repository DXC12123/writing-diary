package com.xiaojingyu.writingdiary;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.graphics.Insets;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.webkit.WebViewAssetLoader;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import android.util.Base64;

/**
 * 写作日记的安卓外壳。
 *
 * 三件事必须做对，否则体验和网页版差很远：
 * 1. 页面挂在 https://appassets.androidplatform.net/assets/ 下（WebViewAssetLoader），
 *    不要用 file:///android_asset/ —— 那样 localStorage 的源是 file://，部分机型会丢数据。
 * 2. 打开 DOM Storage，否则日记根本存不住。
 * 3. 补 onShowFileChooser（选壁纸 / 选 JSON 备份）和「保存到」对话框（导出 txt / json），
 *    网页里靠 <input type=file> 和 <a download> 的东西在 WebView 里不会自己生效。
 */
public class MainActivity extends Activity {

    private static final String HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + HOST + "/assets/index.html";

    private static final int REQ_PICK = 1001;
    private static final int REQ_SAVE = 1002;

    private WebView web;
    private ValueCallback<Uri[]> pickCallback;

    private String pendingText;
    private String pendingName;
    private byte[] pendingBytes;

    // 上一次发给页面的数值，避免键盘动画每一帧都往 WebView 里塞一次 JS
    private int lastTop = -1, lastBottom = -1, lastKb = -1;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        web = new WebView(this);
        web.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(web);

        // 页面铺到状态栏 / 导航栏后面，颜色交给网页自己画（换皮肤时整屏跟着变）
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            getWindow().setStatusBarContrastEnforced(false);
            getWindow().setNavigationBarContrastEnforced(false);
        }

        // 把原生安全区高度告诉页面：页面里用 --sat / --sab 决定头尾留白，
        // 用 --kb 让出输入法高度（键盘弹起时整屏上缩，光标不会被挡住）
        web.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets wi) {
                WindowInsetsCompat c = WindowInsetsCompat.toWindowInsetsCompat(wi, v);
                Insets bars = c.getInsets(WindowInsetsCompat.Type.systemBars()
                        | WindowInsetsCompat.Type.displayCutout());
                Insets ime = c.getInsets(WindowInsetsCompat.Type.ime());
                float d = getResources().getDisplayMetrics().density;
                sendSafeArea(Math.round(bars.top / d), Math.round(bars.bottom / d),
                        Math.round(ime.bottom / d));
                return wi;
            }
        });

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSaveFormData(false);
        s.setGeolocationEnabled(false);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                return loader.shouldInterceptRequest(req.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (u != null && HOST.equals(u.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) {
                    // 没有浏览器可接管就留在原地
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // 页面刚起来时 setSafeArea 可能还没定义，这里清掉缓存再补发一次
                lastTop = lastBottom = lastKb = -1;
                view.requestApplyInsets();
            }
        });

        web.addJavascriptInterface(new HostBridge(), "AndroidHost");

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb,
                                             FileChooserParams params) {
                if (pickCallback != null) pickCallback.onReceiveValue(null);
                pickCallback = cb;

                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType(pickMime(params));
                try {
                    startActivityForResult(Intent.createChooser(i, "选择文件"), REQ_PICK);
                } catch (Exception e) {
                    pickCallback = null;
                    return false;
                }
                return true;
            }
        });

        web.loadUrl(START_URL);
    }

    /** 把安全区高度（dp）写进页面的 CSS 变量：top / bottom 是状态栏与导航栏，kb 是输入法。
     *  页面加载完再补一次（onPageFinished 里清缓存重发），防止首帧漏掉 */
    private void sendSafeArea(int topDp, int bottomDp, int kbDp) {
        if (web == null) return;
        if (topDp == lastTop && bottomDp == lastBottom && kbDp == lastKb) return;
        lastTop = topDp;
        lastBottom = bottomDp;
        lastKb = kbDp;
        final String js = "window.setSafeArea && window.setSafeArea("
                + topDp + "," + bottomDp + "," + kbDp + ")";
        web.post(new Runnable() {
            @Override public void run() {
                web.evaluateJavascript(js, null);
            }
        });
    }

    /** 深色皮肤要浅色图标，浅色皮肤要深色图标 */
    private void applyStatusBarIcons(final boolean darkTheme) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                WindowInsetsControllerCompat c =
                        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
                c.setAppearanceLightStatusBars(!darkTheme);
                c.setAppearanceLightNavigationBars(!darkTheme);
            }
        });
    }

    /** accept 里既有 "application/json" 也有 ".json"，只取能当 MIME 用的那个 */
    private String pickMime(WebChromeClient.FileChooserParams params) {
        String[] types = params == null ? null : params.getAcceptTypes();
        if (types != null) {
            for (String t : types) {
                if (t != null && t.contains("/")) return t;
            }
        }
        return "*/*";
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_PICK) {
            if (pickCallback == null) return;
            Uri[] out = null;
            if (res == RESULT_OK && data != null) {
                if (data.getData() != null) {
                    out = new Uri[]{ data.getData() };
                } else if (data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    out = new Uri[n];
                    for (int i = 0; i < n; i++) out[i] = data.getClipData().getItemAt(i).getUri();
                }
            }
            pickCallback.onReceiveValue(out);
            pickCallback = null;
            return;
        }

        if (req == REQ_SAVE) {
            if (res == RESULT_OK && data != null && data.getData() != null
                    && (pendingText != null || pendingBytes != null)) {
                if (pendingBytes != null) writeBytesTo(data.getData(), pendingBytes, pendingName);
                else writeTo(data.getData(), pendingText, pendingName);
            } else {
                toast("已取消保存");
            }
            pendingText = null;
            pendingBytes = null;
            pendingName = null;
            return;
        }

        super.onActivityResult(req, res, data);
    }

    private void writeTo(Uri uri, String text, String name) {
        try (OutputStream os = getContentResolver().openOutputStream(uri)) {
            if (os == null) throw new IllegalStateException("openOutputStream returned null");
            os.write(text.getBytes(StandardCharsets.UTF_8));
            os.flush();
            toast(name == null ? "已保存" : "已保存：" + name);
        } catch (Exception e) {
            toast("保存失败，换一个位置再试");
        }
    }

    /** 图片这类二进制走这里：页面给 base64，原生解码后按字节写 */
    private void writeBytesTo(Uri uri, byte[] bytes, String name) {
        try (OutputStream os = getContentResolver().openOutputStream(uri)) {
            if (os == null) throw new IllegalStateException("openOutputStream returned null");
            os.write(bytes);
            os.flush();
            toast(name == null ? "已保存" : "已保存：" + name);
        } catch (Exception e) {
            toast("保存失败，换一个位置再试");
        }
    }

    private void toast(final String msg) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** 给页面调的原生能力；页面会先判断 window.AndroidHost 是否存在，网页版不受影响 */
    private class HostBridge {
        /** 页面换皮肤时调这个：darkTheme=true 表示深色底，状态栏图标用浅色 */
        @JavascriptInterface
        public void setStatusBarIcons(final boolean darkTheme) {
            applyStatusBarIcons(darkTheme);
        }

        @JavascriptInterface
        public void saveFile(final String name, final String mime, final String text) {
            pendingName = name;
            pendingText = text;
            pendingBytes = null;
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    String type = (mime == null || mime.isEmpty()) ? "text/plain" : mime.split(";")[0];
                    i.setType(type);
                    if (name != null) i.putExtra(Intent.EXTRA_TITLE, name);
                    try {
                        startActivityForResult(i, REQ_SAVE);
                    } catch (Exception e) {
                        pendingText = null;
                        pendingName = null;
                        toast("打不开保存对话框");
                    }
                }
            });
        }

        /** 图片分享用：base64 → 字节，其余流程和 saveFile 一样 */
        @JavascriptInterface
        public void saveFileBinary(final String name, final String mime, final String base64) {
            final byte[] bytes;
            try {
                bytes = Base64.decode(base64, Base64.DEFAULT);
            } catch (Exception e) {
                toast("图片太大，保存失败");
                return;
            }
            pendingName = name;
            pendingText = null;
            pendingBytes = bytes;
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    String type = (mime == null || mime.isEmpty()) ? "image/png" : mime.split(";")[0];
                    i.setType(type);
                    if (name != null) i.putExtra(Intent.EXTRA_TITLE, name);
                    try {
                        startActivityForResult(i, REQ_SAVE);
                    } catch (Exception e) {
                        pendingBytes = null;
                        pendingName = null;
                        toast("打不开保存对话框");
                    }
                }
            });
        }
    }

    @Override
    public void onBackPressed() {
        // 页面本身没有浏览器历史（单页），返回键直接退出，避免落到白屏
        finish();
    }
}
