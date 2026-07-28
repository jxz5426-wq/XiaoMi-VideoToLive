package com.videotolive.app;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * FFmpeg 管理器
 * - 首次启动从 assets 释放 FFmpeg + ffprobe 二进制
 * - 支持下载模式（网络获取，作为备选）
 * - 自动设置可执行权限
 */
public class FFmpegProvider {

    private static final String TAG = "FFmpeg";
    private static final String BIN_FFMPEG = "ffmpeg";
    private static final String BIN_FFPROBE = "ffprobe";

    /**
     * 获取 FFmpeg 可执行文件路径
     */
    public static String getFfmpegPath(Context ctx) {
        return findBinary(ctx, BIN_FFMPEG);
    }

    /**
     * 获取 ffprobe 可执行文件路径
     */
    public static String getFfprobePath(Context ctx) {
        return findBinary(ctx, BIN_FFPROBE);
    }

    private static String findBinary(Context ctx, String name) {
        // 1) 检查已释放的本地文件
        File local = new File(ctx.getFilesDir(), "bin/" + name);
        if (local.canExecute()) return local.getAbsolutePath();

        // 2) 检查 Termux
        File termux = new File("/data/data/com.termux/files/usr/bin/" + name);
        if (termux.canExecute()) return termux.getAbsolutePath();

        // 3) 检查系统路径
        String[] sysPaths = {"/system/bin/" + name, "/vendor/bin/" + name};
        for (String p : sysPaths) {
            if (new File(p).canExecute()) return p;
        }

        return null;
    }

    /**
     * @deprecated 使用 getFfmpegPath 替代
     */
    @Deprecated
    public static String getPath(Context ctx) {
        return getFfmpegPath(ctx);
    }

    /**
     * 是否已可用
     */
    public static boolean isAvailable(Context ctx) {
        return getFfmpegPath(ctx) != null;
    }

    /**
     * 从 assets 释放 FFmpeg 和 ffprobe
     */
    public static boolean extractFromAssets(Context ctx) {
        boolean ok = extractOne(ctx, BIN_FFMPEG);
        ok |= extractOne(ctx, BIN_FFPROBE);
        return ok;
    }

    private static boolean extractOne(Context ctx, String name) {
        try {
            File binDir = new File(ctx.getFilesDir(), "bin");
            binDir.mkdirs();
            File outFile = new File(binDir, name);

            // 检查版本标记，版本变化则强制重新释放
            File verFile = new File(binDir, ".version");
            String curVer = "2"; // 递增此版本号强制更新二进制
            String oldVer = "";
            try {
                java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(verFile));
                oldVer = br.readLine();
                br.close();
            } catch (Exception ignored) {}
            if (outFile.canExecute() && curVer.equals(oldVer)) return true;
            // 删除旧二进制，重新释放
            outFile.delete();

            // 从 assets 复制
            InputStream is = ctx.getAssets().open(name);
            FileOutputStream fos = new FileOutputStream(outFile);
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
            fos.close();
            is.close();

            // 设置可执行权限
            outFile.setExecutable(true, false);
            outFile.setReadable(true, false);

            // 写版本标记
            try {
                java.io.FileWriter fw = new java.io.FileWriter(new File(binDir, ".version"));
                fw.write("2");
                fw.close();
            } catch (Exception ignored) {}

            Log.d(TAG, name + " 已释放: " + outFile.getAbsolutePath());
            return outFile.canExecute();

        } catch (Exception e) {
            Log.e(TAG, "释放 " + name + " 失败", e);
            return false;
        }
    }

    /**
     * 从网络下载 FFmpeg（备选方案，适用于 assets 未打包的场景）
     */
    public static void downloadAsync(Context ctx, Callback cb) {
        new Thread(() -> {
            try {
                File binDir = new File(ctx.getFilesDir(), "bin");
                binDir.mkdirs();
                File outFile = new File(binDir, BIN_FFMPEG);

                // 检查网络连通性
                if (!checkNetwork()) {
                    if (cb != null) cb.onError("无网络连接\n\n请连接网络后重试，或安装 Termux 并运行:\npkg install ffmpeg");
                    return;
                }

                String url = "https://github.com/nickaknudson/android-ffmpeg/releases/download/v1.0/ffmpeg-arm64";

                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(60000);
                conn.connect();

                int code = conn.getResponseCode();
                if (code != 200) {
                    if (cb != null) cb.onError("下载失败 (HTTP " + code + ")\n\n请安装 Termux 并运行:\npkg install ffmpeg");
                    return;
                }

                int total = conn.getContentLength();
                InputStream is = conn.getInputStream();
                FileOutputStream fos = new FileOutputStream(outFile);
                byte[] buf = new byte[8192];
                int n, downloaded = 0;
                while ((n = is.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                    downloaded += n;
                    if (cb != null && total > 0)
                        cb.onProgress((int) (downloaded * 100 / total));
                }
                fos.close();
                is.close();

                outFile.setExecutable(true, false);

                if (outFile.canExecute()) {
                    if (cb != null) cb.onSuccess(outFile.getAbsolutePath());
                } else {
                    if (cb != null) cb.onError("文件无法执行\n\n请安装 Termux 并运行:\npkg install ffmpeg");
                }
            } catch (java.net.SocketTimeoutException e) {
                if (cb != null) cb.onError("下载超时\n\n网络较慢或 GitHub 不可达\n请安装 Termux 并运行:\npkg install ffmpeg");
            } catch (java.net.UnknownHostException e) {
                if (cb != null) cb.onError("无法访问下载服务器\n\nGitHub 可能不可达\n请安装 Termux 并运行:\npkg install ffmpeg");
            } catch (Exception e) {
                if (cb != null) cb.onError("下载失败: " + e.getMessage() + "\n\n请安装 Termux 并运行:\npkg install ffmpeg");
            }
        }).start();
    }

    private static boolean checkNetwork() {
        try {
            java.net.InetAddress.getByName("github.com");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public interface Callback {
        void onProgress(int pct);
        void onSuccess(String path);
        void onError(String err);
    }
}
