package com.videotolive.app;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 视频转 Live Photo 核心引擎
 */
public class Converter {

    private static final String TAG = "Converter";
    private static String sFfmpegPath;
    private static String sFfprobePath;

    public static void setFfmpegPath(String p) { sFfmpegPath = p; }
    public static void setFfprobePath(String p) { sFfprobePath = p; }

    // ---------- 日志回调 ----------

    public interface LogListener {
        void onLog(String line);
    }

    private static LogListener sLogListener;

    public static void setLogListener(LogListener l) { sLogListener = l; }

    private static void log(String msg) {
        Log.d(TAG, msg);
        if (sLogListener != null) sLogListener.onLog(msg);
    }

    public static class Options {
        public double startSec = 0;
        public double coverSec = 0.5;
        public double maxSec = 3.9;
        public int maxW = 1080;
        public int maxH = 1080;
        public int quality = 1;  // 0=极速 1=推荐 2=高清 3=无损
        public int crf = 25;     // 由 quality 决定
        public int gpuBitrate = 15_000_000; // 由 quality 决定
    }

    /** 根据 quality 等级设置编码参数 */
    public static void applyQuality(Options o) {
        switch (o.quality) {
            case 0: o.crf = 30; o.gpuBitrate = 6_000_000; break;  // 极速
            case 1: o.crf = 25; o.gpuBitrate = 15_000_000; break; // 推荐
            case 2: o.crf = 20; o.gpuBitrate = 25_000_000; break; // 高清
            case 3: o.crf = 15; o.gpuBitrate = 40_000_000; break; // 无损
            default: o.crf = 25; o.gpuBitrate = 15_000_000; break;
        }
    }

    public interface Callback {
        void onProgress(String msg, int pct);
        void onSuccess(String path);
        void onError(String err);
    }

    // ---------- 二进制执行 ----------

    private static class ExecResult {
        boolean ok;
        String err = "";
        String out = "";
    }

    /**
     * 执行二进制。优先 linker64（绕过 SELinux），因为 sh -c 和直接 exec 在高版本 Android 上必然 Permission denied
     */
    private static ExecResult exec(String binary, String... args) {
        StringBuilder fullCmd = new StringBuilder(binary);
        for (String a : args) fullCmd.append(" ").append(a);

        // 优先 linker64
        List<String> linkerArgs = new ArrayList<>();
        linkerArgs.add(binary);
        for (String a : args) linkerArgs.add(a);
        ExecResult r = execDirect("/system/bin/linker64", linkerArgs.toArray(new String[0]));
        if (r.ok) return r;
        // 保留 linker64 的真实错误（不会被后续 Permission denied 覆盖）
        String linkerErr = r.err;

        // linker64 失败才尝试其他方式
        log("▶ " + fullCmd.toString());
        log("  linker64 失败: " + truncate(linkerErr));

        r = execSh(binary, args);
        if (r.ok) return r;

        r = execDirect(binary, args);
        if (r.ok) return r;

        // 返回 linker64 的真实错误而非 Permission denied
        r.err = linkerErr;
        return r;
    }

    private static String truncate(String s) {
        if (s == null || s.isEmpty()) return "(无输出)";
        if (s.length() > 200) return s.substring(s.length() - 200);
        return s;
    }

    /** 通过 sh -c 执行 */
    private static ExecResult execSh(String binary, String... args) {
        ExecResult r = new ExecResult();
        try {
            StringBuilder cmd = new StringBuilder();
            cmd.append("'").append(binary).append("'");
            for (String a : args) {
                cmd.append(" '").append(a.replace("'", "'\\''")).append("'");
            }
            log("  sh -c " + cmd.toString());

            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd.toString()});
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Thread t1 = new Thread(() -> {
                try {
                    InputStream e = p.getErrorStream();
                    byte[] b = new byte[4096];
                    int n;
                    while ((n = e.read(b)) > 0) baos.write(b, 0, n);
                } catch (Exception ignored) {}
            });
            Thread t2 = new Thread(() -> {
                try {
                    InputStream s = p.getInputStream();
                    byte[] b = new byte[4096];
                    int n;
                    while ((n = s.read(b)) > 0) baos.write(b, 0, n);
                } catch (Exception ignored) {}
            });
            t1.start(); t2.start();
            t1.join(30000); t2.join(30000);

            boolean done = p.waitFor(300, java.util.concurrent.TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                r.ok = false;
                r.err = "sh -c 执行超时（>5分钟）";
                return r;
            }

            int rc = p.exitValue();
            r.ok = (rc == 0);
            String output = baos.toString("UTF-8");
            r.out = output;
            if (!r.ok) {
                r.err = output.length() > 500 ? "…" + output.substring(output.length() - 500) : output;
                if (r.err.isEmpty()) r.err = "(无输出, rc=" + rc + ")";
                log("  stderr: " + r.err.replace("\n", "\n  "));
            }
        } catch (Exception e) {
            r.ok = false;
            r.err = e.getClass().getSimpleName() + ": " + e.getMessage();
            log("  ✗ sh exec 异常: " + r.err);
        }
        return r;
    }

    /** ProcessBuilder 直接执行 */
    private static ExecResult execDirect(String binary, String... args) {
        ExecResult r = new ExecResult();
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(binary);
            for (String a : args) cmd.add(a);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();

            // 读输出（最多 64KB，防止 OOM）
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            InputStream is = p.getInputStream();
            int n, total = 0;
            while ((n = is.read(buf)) > 0 && total < 65536) {
                baos.write(buf, 0, n);
                total += n;
            }
            // 丢弃剩余输出（避免阻塞子进程）
            if (total >= 65536) {
                byte[] discard = new byte[4096];
                while (is.read(discard) > 0) {}
            }

            // 最多等 2 分钟（ultrafast 编码 4 秒视频应在几十秒内完成）
            boolean done = p.waitFor(120, java.util.concurrent.TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                r.ok = false;
                r.err = "执行超时（>10分钟）";
                return r;
            }

            int rc = p.exitValue();
            r.ok = (rc == 0);
            String output = baos.toString("UTF-8");
            r.out = output;
            if (!r.ok) {
                r.err = output.length() > 500 ? "…" + output.substring(output.length() - 500) : output;
                if (r.err.isEmpty()) r.err = "(无输出, rc=" + rc + ")";
            }
        } catch (Exception e) {
            r.ok = false;
            r.err = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        return r;
    }

    // ---------- 编码器探测 ----------

    /** 执行 ffmpeg 命令并返回原始输出（公开，供调试用） */
    public static String runFfmpegCmd(String... args) {
        String binary = sFfmpegPath != null ? sFfmpegPath : "ffmpeg";
        // 先尝试直接 exec（对 linker64 我们已在 exec 里处理）
        ExecResult r = exec(binary, args);
        return r.ok ? r.out : r.err;
    }

    // ---------- 视频信息 ----------

    private static int getVideoWidth(String path) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(path);
            String w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            return w != null ? Integer.parseInt(w) : 0;
        } catch (Exception e) { return 0; }
        finally { try { mmr.release(); } catch (Exception ignored) {} }
    }

    private static int getVideoHeight(String path) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(path);
            String h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            return h != null ? Integer.parseInt(h) : 0;
        } catch (Exception e) { return 0; }
        finally { try { mmr.release(); } catch (Exception ignored) {} }
    }

    // ---------- 探测视频时长 ----------

    public static double probeDuration(String videoPath) {
        try {
            File vf = new File(videoPath);
            if (!vf.exists()) return -1;

            String ffprobe = sFfprobePath != null ? sFfprobePath : "ffprobe";
            ExecResult r = exec(ffprobe,
                "-v", "error",
                "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1",
                videoPath);
            if (r.ok && r.out != null && !r.out.isEmpty()) {
                double d = Double.parseDouble(r.out.trim());
                log(String.format("视频时长: %.1f秒", d));
                return d;
            }
        } catch (Exception e) {
            log("probeDuration 异常: " + e.getMessage());
        }
        return -1;
    }

    public static double probeDuration(Context ctx, Uri uri) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            String scheme = uri.getScheme();
            if ("file".equals(scheme)) {
                mmr.setDataSource(uri.getPath());
            } else {
                mmr.setDataSource(ctx, uri);
            }
            String dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (dur != null) {
                double d = Double.parseDouble(dur) / 1000.0;
                log(String.format("视频时长(MMR): %.1f秒", d));
                return d;
            }
        } catch (Exception e) {
            log("probeDuration MMR 异常: " + e.getMessage());
        } finally {
            try { mmr.release(); } catch (Exception ignored) {}
        }
        return -1;
    }

    // ---------- 从 URI 转换 ----------
    public static void fromUri(Context ctx, Uri uri, Options opt, Callback cb) {
        new Thread(() -> {
            try {
                String path = UriHelper.getPath(ctx, uri);
                if (path == null) path = copyToCache(ctx, uri);
                if (path == null) { cb.onError("无法读取视频"); return; }
                fromFileSync(path, opt, cb);  // 在当前线程执行，不额外开线程
            } catch (Exception e) {
                cb.onError("失败: " + e.getMessage());
            }
        }).start();
    }

    // ---------- 从文件转换（异步，在后台线程执行）----------
    public static void fromFile(String in, Options opt, Callback cb) {
        new Thread(() -> fromFileSync(in, opt, cb)).start();
    }

    private static void fromFileSync(String in, Options opt, Callback cb) {
        if (opt == null) opt = new Options();
        File inp = new File(in);
        if (!inp.exists()) { cb.onError("文件不存在"); return; }

        log("输入文件: " + in);
        String[] qn = {"极速","推荐","高清","无损"};
        log(String.format("参数: start=%.1f maxSec=%.1f cover=%.1f 画质=%s(crf=%d,bps=%.1fM)",
            opt.startSec, opt.maxSec, opt.coverSec,
            qn[opt.quality], opt.crf, opt.gpuBitrate / 1_000_000.0));

        if (opt.maxSec <= 0) {
            double d = probeDuration(in);
            if (d > 0) opt.maxSec = d;
            else { cb.onError("无法获取视频时长"); return; }
        }

        File tmp = new File(inp.getParentFile(), ".vtl_" + System.currentTimeMillis());
        tmp.mkdirs();
        log("临时目录: " + tmp.getAbsolutePath());

        try {
            // 1) 封面
            cb.onProgress("截取封面…", 10);
            File cover = new File(tmp, "cover.jpg");
            ExecResult r1 = ffmpeg(
                "-y", "-ss", String.format("%.2f", opt.startSec + opt.coverSec),
                "-i", in,
                "-vframes", "1", "-q:v", "2",
                cover.getAbsolutePath());
            if (!r1.ok) {
                cb.onError("封面截取失败\n" + r1.err);
                return;
            }
            log("封面: " + cover.length() + " bytes");

            // 2) 转码：GPU 硬编优先，软编兜底
            cb.onProgress("处理视频…", 30);
            File vid = new File(tmp, "video.mp4");

            int vw = getVideoWidth(in);
            int vh = getVideoHeight(in);
            boolean needScale = (vw > 1080 || vh > 1080);
            String scaleFilter = null;
            if (needScale) {
                // 简单的比例缩放，不依赖新版 ffmpeg 的复杂选项
                scaleFilter = (vw >= vh)
                    ? "scale=1080:-2"   // 横屏：宽度限 1080，高度自适应
                    : "scale=-2:1080";  // 竖屏：高度限 1080，宽度自适应
            }

            // 编码策略：GPU → 流拷贝 → CPU
            Object[][] encoderAttempts = {
                {"__gpu__", "GPU MediaCodec"},
                {"copy", "流拷贝 -c:v copy"},
                {"libx264", "-preset", "veryfast", "-tune", "zerolatency",
                 "-crf", String.valueOf(opt.crf), "-r", "30", "CPU libx264"},
            };

            ExecResult r2 = null;
            for (Object[] enc : encoderAttempts) {
                String codec = (String) enc[0];
                String label = (String) enc[enc.length - 1];
                log("编码: " + label);

                // ── GPU 路径 ──
                if ("__gpu__".equals(codec)) {
                    File gpuOut = new File(tmp, "gpu_video.mp4");
                    boolean ok = GpuConverter.transcode(in, gpuOut.getAbsolutePath(),
                        opt.startSec, opt.maxSec, opt.maxW, opt.maxH, opt.gpuBitrate,
                        msg -> log("  GPU: " + msg));
                    if (ok && gpuOut.length() > 0) {
                        // ffmpeg 合并音频（流拷贝，很快）
                        ExecResult merge = ffmpeg(
                            "-y", "-i", gpuOut.getAbsolutePath(),
                            "-ss", String.format("%.2f", opt.startSec), "-i", in,
                            "-t", String.format("%.2f", opt.maxSec),
                            "-c", "copy", "-map", "0:v:0", "-map", "1:a:0?",
                            "-movflags", "+faststart",
                            vid.getAbsolutePath());
                        r2 = (merge.ok) ? merge : null;
                        // 合并失败则直接用纯视频（无音频）
                        if (!merge.ok && gpuOut.renameTo(vid)) {
                            r2 = new ExecResult(); r2.ok = true;
                        }
                    }
                    if (r2 != null && r2.ok) break;
                    log("  GPU 不可用, 回退");
                    cb.onProgress("⚠GPU不可用·CPU编码较慢", -1);
                    continue;
                }

                // ── CPU / 流拷贝 路径 ──
                List<String> args = new ArrayList<>();
                args.add("-y");
                if (opt.startSec > 0.01) { args.add("-ss"); args.add(String.format("%.2f", opt.startSec)); }
                args.add("-i"); args.add(in);
                args.add("-t"); args.add(String.format("%.2f", opt.maxSec));
                args.add("-c:v"); args.add(codec);
                for (int i = 1; i < enc.length - 1; i++) args.add((String) enc[i]);

                if (needScale && codec.startsWith("lib")) {
                    args.add("-vf"); args.add(scaleFilter);
                    args.add("-pix_fmt"); args.add("yuv420p");
                    log("  缩放 " + vw + "x" + vh + " → ≤1080p");
                }

                for (String audio : new String[] {"-c:a copy", "-c:a aac -b:a 128k"}) {
                    List<String> a = new ArrayList<>(args);
                    for (String pa : audio.split(" ")) a.add(pa);
                    a.add("-movflags"); a.add("+faststart");
                    a.add(vid.getAbsolutePath());
                    r2 = ffmpeg(a.toArray(new String[0]));
                    if (r2.ok) break;
                }
                if (r2 != null && r2.ok) {
                    double mbps = vid.length() / (opt.maxSec * 1024.0 * 1024.0);
                    if ("copy".equals(codec) && mbps > 5) {
                        log(String.format("流拷贝 %.1fMB/s 过大, 改重编码", mbps));
                        r2.ok = false;
                        continue;
                    }
                    log(String.format("转码后视频: %d bytes (%.1fMB/s)", vid.length(), mbps));
                    break;
                }
                log("  " + label + " 失败: " + truncate(r2 != null ? r2.err : "无输出"));
            }

            if (r2 == null || !r2.ok) {
                cb.onError("视频转码失败\n" + (r2 != null ? r2.err : "所有编码方式均失败"));
                return;
            }

            // 3) 注入 XMP
            cb.onProgress("写入元数据…", 60);
            long vidSize = vid.length();
            File coverXmp = new File(tmp, "cover_xmp.jpg");
            injectXmp(cover, coverXmp, vidSize);

            // 4) 拼接 → DCIM/LivePhotos/
            cb.onProgress("组装…", 80);
            String base = inp.getName();
            int dot = base.lastIndexOf('.');
            if (dot > 0) base = base.substring(0, dot);

            File outDir = new File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DCIM), "Live");
            outDir.mkdirs();
            // 避免 EEXIST：用时间戳 + 计数器确保唯一文件名
            String ts = new java.text.SimpleDateFormat("HHmmss", java.util.Locale.getDefault()).format(new java.util.Date());
            File out = new File(outDir, base + "_" + ts + "_live.jpg");
            int s = 1;
            while (out.exists()) { out = new File(outDir, base + "_" + ts + "_live_" + s + ".jpg"); s++; }

            cat(coverXmp, vid, out);
            log("输出: " + out.getAbsolutePath() + " (" + out.length() + " bytes)");

            // 5) 通知媒体库
            try {
                Runtime.getRuntime().exec(new String[]{"am", "broadcast",
                        "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
                        "-d", "file://" + out.getAbsolutePath()});
            } catch (Exception ignored) {}

            cb.onProgress("完成", 100);
            cb.onSuccess(out.getAbsolutePath());

        } catch (Exception e) {
            log("异常: " + e.getMessage());
            cb.onError("异常: " + e.getMessage());
        } finally {
            del(tmp);
            log("清理临时文件");
        }
    }

    // ---------- 调用 FFmpeg ----------
    private static ExecResult ffmpeg(String... args) {
        String binary = sFfmpegPath != null ? sFfmpegPath : "ffmpeg";
        return exec(binary, args);
    }

    // ---------- XMP 注入 ----------
    private static void injectXmp(File jpgIn, File jpgOut, long videoSize) throws Exception {
        byte[] jpg = read(jpgIn);

        // 计算注入后的 JPEG 部分大小
        byte[] ns = "http://ns.adobe.com/xap/1.0/\0".getBytes(StandardCharsets.UTF_8);

        String xmp =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"VideoToLive\">\n" +
            " <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n" +
            "  <rdf:Description rdf:about=\"\"\n" +
            "    xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\"\n" +
            "    xmlns:Container=\"http://ns.google.com/photos/1.0/container/\"\n" +
            "    xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\"\n" +
            "    GCamera:MotionPhoto=\"1\"\n" +
            "    GCamera:MotionPhotoVersion=\"1\"\n" +
            "    GCamera:MotionPhotoPresentationTimestampUs=\"0\">\n" +
            "   <Container:Directory>\n" +
            "    <rdf:Seq>\n" +
            "     <rdf:li rdf:parseType=\"Resource\">\n" +
            "      <Item:Mime>image/jpeg</Item:Mime>\n" +
            "      <Item:Semantic>Primary</Item:Semantic>\n" +
            "      <Item:Length>__IMAGE_SIZE__</Item:Length>\n" +
            "      <Item:Padding>0</Item:Padding>\n" +
            "     </rdf:li>\n" +
            "     <rdf:li rdf:parseType=\"Resource\">\n" +
            "      <Item:Mime>video/mp4</Item:Mime>\n" +
            "      <Item:Semantic>MotionPhoto</Item:Semantic>\n" +
            "      <Item:Length>" + videoSize + "</Item:Length>\n" +
            "      <Item:Padding>0</Item:Padding>\n" +
            "     </rdf:li>\n" +
            "    </rdf:Seq>\n" +
            "   </Container:Directory>\n" +
            "  </rdf:Description>\n" +
            " </rdf:RDF>\n" +
            "</x:xmpmeta>";

        // APP1 段：FF E1 [2-byte length] [payload] = 4 + payload.length 字节
        // 注入后的 JPEG 大小 = 原图 + APP1 段大小
        int addedBytes = 4 + ns.length + xmp.length();

        // 替换占位符为实际 JPEG 大小
        xmp = xmp.replace("__IMAGE_SIZE__", String.valueOf(jpg.length + addedBytes));

        byte[] xd = xmp.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[ns.length + xd.length];
        System.arraycopy(ns, 0, payload, 0, ns.length);
        System.arraycopy(xd, 0, payload, ns.length, xd.length);

        int segLen = payload.length + 2;
        // 在 JPEG 扫描数据前 (SOS 前) 注入 XMP APP1 段
        // 注意：必须放在 SOS 之前，小米相册才能识别为动态照片
        int pos = find(jpg, (byte) 0xFF, (byte) 0xDA); // SOS marker
        if (pos < 0) pos = find(jpg, (byte) 0xFF, (byte) 0xD9); // EOI fallback
        if (pos < 0) pos = jpg.length - 2;

        byte[] out = new byte[pos + 4 + payload.length + (jpg.length - pos)];
        int w = 0;
        System.arraycopy(jpg, 0, out, w, pos); w += pos;
        out[w++] = (byte) 0xFF;
        out[w++] = (byte) 0xE1;
        out[w++] = (byte) ((segLen >> 8) & 0xFF);
        out[w++] = (byte) (segLen & 0xFF);
        System.arraycopy(payload, 0, out, w, payload.length); w += payload.length;
        System.arraycopy(jpg, pos, out, w, jpg.length - pos);

        write(jpgOut, out);
    }

    private static int find(byte[] d, byte a, byte b) {
        for (int i = 0; i < d.length - 1; i++)
            if (d[i] == a && d[i + 1] == b) return i;
        return -1;
    }

    private static void cat(File a, File b, File out) throws Exception {
        try (FileOutputStream fo = new FileOutputStream(out);
             FileInputStream fa = new FileInputStream(a);
             FileInputStream fb = new FileInputStream(b)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = fa.read(buf)) > 0) fo.write(buf, 0, n);
            while ((n = fb.read(buf)) > 0) fo.write(buf, 0, n);
        }
    }

    private static byte[] read(File f) throws Exception {
        try (FileInputStream fi = new FileInputStream(f)) {
            byte[] d = new byte[(int) f.length()];
            fi.read(d);
            return d;
        }
    }

    private static void write(File f, byte[] d) throws Exception {
        try (FileOutputStream fo = new FileOutputStream(f)) { fo.write(d); }
    }

    private static String copyToCache(Context ctx, Uri uri) {
        try {
            File dir = new File(ctx.getCacheDir(), "vtl");
            dir.mkdirs();
            File f = new File(dir, "in_" + System.currentTimeMillis() + ".mp4");
            try (java.io.InputStream is = ctx.getContentResolver().openInputStream(uri);
                 FileOutputStream fo = new FileOutputStream(f)) {
                if (is == null) return null;
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) fo.write(buf, 0, n);
            }
            return f.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    private static void del(File f) {
        if (f.isDirectory()) {
            File[] ch = f.listFiles();
            if (ch != null) for (File c : ch) del(c);
        }
        f.delete();
    }
}
