package com.damien.youyu;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.widget.Toast;

/**
 * 普通（http/https）下载的兜底处理，交给系统 DownloadManager。
 *
 * 说明：应用现有的导出功能走的是 Blob（见 MainActivity 里注入的劫持脚本），不会走到这里。
 * 保留本监听器是为了将来若出现服务端直链下载，不至于点了没反应。
 * blob: 在此明确忽略——WebView 拿不到它的字节，交给 DownloadManager 只会失败。
 */
public class YouyuDownloadListener implements DownloadListener {

    private final Activity activity;

    public YouyuDownloadListener(Activity activity) {
        this.activity = activity;
    }

    @Override
    public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                String mimeType, long contentLength) {
        if (url == null) return;

        if (url.startsWith("blob:")) {
            // 正常情况下注入的脚本已接管；走到这说明劫持没生效，给用户一个明确反馈而不是静默失败。
            Toast.makeText(activity, "该文件无法直接保存，请稍后重试", Toast.LENGTH_LONG).show();
            return;
        }
        if (!URLUtil.isNetworkUrl(url)) return;

        try {
            String filename = URLUtil.guessFileName(url, contentDisposition, mimeType);

            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.setMimeType(mimeType);
            req.addRequestHeader("User-Agent", userAgent);
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) req.addRequestHeader("Cookie", cookie);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);
            req.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);

            DownloadManager dm = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) {
                Toast.makeText(activity, "系统下载服务不可用", Toast.LENGTH_SHORT).show();
                return;
            }
            dm.enqueue(req);
            Toast.makeText(activity, "开始下载：" + filename, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(activity, "下载失败", Toast.LENGTH_SHORT).show();
        }
    }
}
