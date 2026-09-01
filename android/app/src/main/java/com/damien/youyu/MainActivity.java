package com.damien.youyu;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.OutputStream;

/**
 * 「有余」Android 外壳。
 *
 * 设计取向：本 apk 只是一层 WebView 壳，真正的应用是线上已部署的 uni-app H5（/app/）。
 * 这样做的收益是刻意的：
 *   - 与站点同源，请求 /api 无跨域问题，后端无需开 CORS；
 *   - 直接继承站点已有的 Service Worker，离线能力不用在原生侧重做一遍；
 *   - 改前端只要部署，apk 不必重新分发。
 * 代价是首次启动必须联网（要先装上 Service Worker）。
 *
 * 刻意不依赖 androidx/任何第三方库，只用 framework API，以便构建环境要求最低。
 */
public class MainActivity extends Activity {

    /** 应用入口。与 manifest.json 的 start_url 一致。 */
    private static final String START_URL = "https://youyuji.com/app/";

    /** 站内域名。此域之外的导航一律交给系统浏览器，避免外部页面落在本壳里。 */
    private static final String HOST = "youyuji.com";

    private static final int REQ_FILE_CHOOSER = 1001;

    private WebView web;

    /** onShowFileChooser 的回调，等 onActivityResult 回填选中的文件。 */
    private ValueCallback<Uri[]> pendingFileCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        // DOM storage 必需：应用用 localStorage 存登录令牌与离线数据，且 Service Worker 依赖它。
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        // 站点全 https，无需放开混合内容。
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        // 关掉缩放：应用自身 viewport 已设 user-scalable=no，双指缩放只会让布局错乱。
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);

        // 只把桥暴露给自己的站点（导航被 shouldOverrideUrlLoading 限制在 HOST 内）。
        web.addJavascriptInterface(new NativeBridge(), "YouyuNative");

        web.setWebViewClient(new YouyuWebViewClient());
        web.setWebChromeClient(new YouyuWebChromeClient());
        web.setDownloadListener(new YouyuDownloadListener(this));

        if (savedInstanceState == null) {
            web.loadUrl(START_URL);
        } else {
            web.restoreState(savedInstanceState);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    /** 返回键优先在 WebView 里回退历史，走到头才退出应用。 */
    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_FILE_CHOOSER) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        if (pendingFileCallback == null) return;

        Uri[] result = null;
        if (resultCode == RESULT_OK && data != null) {
            if (data.getDataString() != null) {
                result = new Uri[]{ Uri.parse(data.getDataString()) };
            } else if (data.getClipData() != null && data.getClipData().getItemCount() > 0) {
                result = new Uri[]{ data.getClipData().getItemAt(0).getUri() };
            }
        }
        // 必须回调（哪怕是 null），否则 <input type=file> 会卡死，之后再点没反应。
        pendingFileCallback.onReceiveValue(result);
        pendingFileCallback = null;
    }

    // ---------------------------------------------------------------- WebViewClient

    private class YouyuWebViewClient extends WebViewClient {

        /** 站内 URL 留在壳里；外域、mailto、tel 等交给系统处理。 */
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            String scheme = uri.getScheme();
            String host = uri.getHost();

            boolean inSite = ("https".equals(scheme) || "http".equals(scheme))
                    && host != null && (host.equals(HOST) || host.endsWith("." + HOST));
            if (inSite) return false;

            try {
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
            } catch (ActivityNotFoundException e) {
                Toast.makeText(MainActivity.this, "没有可打开该链接的应用", Toast.LENGTH_SHORT).show();
            }
            return true;
        }

        /**
         * 页面加载完成后注入下载劫持脚本。
         *
         * 为什么需要：应用导出数据的实现是 fetch → Blob → URL.createObjectURL → <a download>.click()
         * （见 miniapp/src/api/dataio.js）。WebView 拿不到 blob: 的字节，DownloadListener 对它
         * 也不可靠，点「导出」会毫无反应。这里劫持 <a>.click()，把 blob 读成 data URL 交给原生落盘。
         */
        @Override
        public void onPageFinished(WebView view, String url) {
            view.evaluateJavascript(BLOB_DOWNLOAD_HOOK, null);
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            // 只对主文档的失败展示错误页，子资源失败（图标等）不该整页替换。
            if (request == null || !request.isForMainFrame()) return;
            view.loadDataWithBaseURL(null, ERROR_PAGE_HTML, "text/html", "UTF-8", null);
        }
    }

    // ------------------------------------------------------------ WebChromeClient

    private class YouyuWebChromeClient extends WebChromeClient {

        /** 支撑数据导入 / 账单导入里的 <input type=file>（uni.chooseFile 在 H5 端的落地形式）。 */
        @Override
        public boolean onShowFileChooser(WebView view,
                                         ValueCallback<Uri[]> filePathCallback,
                                         FileChooserParams params) {
            // 上一次的回调若还挂着，先释放，避免 WebView 认为选择器仍在占用。
            if (pendingFileCallback != null) {
                pendingFileCallback.onReceiveValue(null);
            }
            pendingFileCallback = filePathCallback;

            // 一律用 */*：页面给的 accept 是 ".json" 这类扩展名而非 MIME，
            // 直接塞给 Intent 会让文件选择器把候选文件全过滤掉。
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");

            try {
                startActivityForResult(Intent.createChooser(intent, "选择文件"), REQ_FILE_CHOOSER);
            } catch (ActivityNotFoundException e) {
                pendingFileCallback = null;
                Toast.makeText(MainActivity.this, "没有可用的文件选择器", Toast.LENGTH_SHORT).show();
                filePathCallback.onReceiveValue(null);
            }
            return true;
        }
    }

    // ------------------------------------------------------------- 原生桥（落盘）

    /**
     * 暴露给页面的最小桥面：只有一个「把 data URL 存进下载目录」的方法。
     * 导航已被限制在 youyuji.com 内，故桥不会暴露给外部页面。
     */
    private class NativeBridge {

        @JavascriptInterface
        public void saveDataUrl(String dataUrl, String filename) {
            if (dataUrl == null || !dataUrl.startsWith("data:")) return;

            int comma = dataUrl.indexOf(',');
            if (comma < 0) return;

            String meta = dataUrl.substring(5, comma);   // 形如 application/json;base64
            String payload = dataUrl.substring(comma + 1);
            if (!meta.contains("base64")) return;        // 只处理 base64，FileReader 产出的即是

            String mime = meta.split(";")[0];
            if (mime.isEmpty()) mime = "application/octet-stream";

            final byte[] bytes;
            try {
                bytes = Base64.decode(payload, Base64.DEFAULT);
            } catch (IllegalArgumentException e) {
                toastOnUi("导出失败：数据解码错误");
                return;
            }

            String safeName = sanitize(filename);
            if (writeToDownloads(safeName, mime, bytes)) {
                toastOnUi("已保存到下载目录：" + safeName);
            } else {
                toastOnUi("导出失败：无法写入下载目录");
            }
        }
    }

    /** 去掉路径分隔符等危险字符，避免写到预期之外的位置。 */
    private static String sanitize(String name) {
        if (name == null || name.trim().isEmpty()) return "youyu-export";
        String n = name.replaceAll("[/\\\\:*?\"<>|]", "_").trim();
        return n.isEmpty() ? "youyu-export" : n;
    }

    /**
     * 通过 MediaStore 写入公共下载目录。
     * API 29+ 走这条路无需任何存储权限，这也是 minSdk 定在 29 的原因。
     */
    private boolean writeToDownloads(String name, String mime, byte[] bytes) {
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
        cv.put(MediaStore.Downloads.MIME_TYPE, mime);
        cv.put(MediaStore.Downloads.IS_PENDING, 1);

        Uri target = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
        if (target == null) return false;

        try (OutputStream out = getContentResolver().openOutputStream(target)) {
            if (out == null) return false;
            out.write(bytes);
            out.flush();
        } catch (Exception e) {
            getContentResolver().delete(target, null, null);
            return false;
        }

        cv.clear();
        cv.put(MediaStore.Downloads.IS_PENDING, 0);
        getContentResolver().update(target, cv, null, null);
        return true;
    }

    private void toastOnUi(final String msg) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
            }
        });
    }

    // ----------------------------------------------------------------- 注入的资源

    /**
     * 劫持带 download 属性、且 href 为 blob: 的 <a>.click()，转交原生保存。
     * 幂等（__youyuDownloadHooked 标记），因为 onPageFinished 可能多次触发。
     * 非 blob 的下载不拦，继续走原生 DownloadListener。
     */
    private static final String BLOB_DOWNLOAD_HOOK =
            "(function(){"
          + "  if (window.__youyuDownloadHooked) return;"
          + "  window.__youyuDownloadHooked = true;"
          + "  var origClick = HTMLAnchorElement.prototype.click;"
          + "  HTMLAnchorElement.prototype.click = function(){"
          + "    try {"
          + "      var href = this.href || '';"
          + "      var dl = this.getAttribute('download');"
          + "      if (dl && href.indexOf('blob:') === 0) {"
          + "        var name = dl;"
          + "        fetch(href).then(function(r){ return r.blob(); }).then(function(b){"
          + "          var fr = new FileReader();"
          + "          fr.onloadend = function(){"
          + "            try { YouyuNative.saveDataUrl(String(fr.result), name); } catch (e) {}"
          + "          };"
          + "          fr.readAsDataURL(b);"
          + "        }).catch(function(){});"
          + "        return;"
          + "      }"
          + "    } catch (e) {}"
          + "    return origClick.apply(this, arguments);"
          + "  };"
          + "})();";

    /** 断网 / 主文档加载失败时的兜底页。重试用普通链接，不经 JS 桥。 */
    private static final String ERROR_PAGE_HTML =
            "<!DOCTYPE html><html lang='zh-CN'><head><meta charset='utf-8'>"
          + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
          + "<style>"
          + "body{margin:0;min-height:100vh;display:flex;flex-direction:column;"
          + "align-items:center;justify-content:center;background:#eef0f2;"
          + "font-family:-apple-system,system-ui,sans-serif;color:#16181c}"
          + ".mk{width:64px;height:64px;border-radius:18px;background:#16a34a;color:#fff;"
          + "font-size:34px;font-weight:800;display:flex;align-items:center;"
          + "justify-content:center;margin-bottom:18px}"
          + "h1{font-size:17px;margin:0 0 8px}p{font-size:13px;color:#6b7280;margin:0 0 22px}"
          + "a{background:#12a150;color:#fff;text-decoration:none;padding:11px 30px;"
          + "border-radius:22px;font-size:14px}"
          + "</style></head><body>"
          + "<div class='mk'>&yen;</div>"
          + "<h1>连不上服务器</h1>"
          + "<p>检查一下网络，然后重试</p>"
          + "<a href='" + START_URL + "'>重试</a>"
          + "</body></html>";
}
