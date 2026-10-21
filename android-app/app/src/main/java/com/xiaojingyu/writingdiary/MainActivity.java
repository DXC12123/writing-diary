package com.xiaojingyu.writingdiary;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

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

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        web = new WebView(this);
        web.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(web);

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
            if (res == RESULT_OK && data != null && data.getData() != null && pendingText != null) {
                writeTo(data.getData(), pendingText, pendingName);
            } else {
                toast("已取消保存");
            }
            pendingText = null;
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

    private void toast(final String msg) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** 给页面调的原生能力；页面会先判断 window.AndroidHost 是否存在，网页版不受影响 */
    private class HostBridge {
        @JavascriptInterface
        public void saveFile(final String name, final String mime, final String text) {
            pendingName = name;
            pendingText = text;
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
    }

    @Override
    public void onBackPressed() {
        // 页面本身没有浏览器历史（单页），返回键直接退出，避免落到白屏
        finish();
    }
}
