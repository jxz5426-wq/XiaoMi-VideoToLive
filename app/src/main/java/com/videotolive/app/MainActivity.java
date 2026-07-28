package com.videotolive.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.Manifest;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.View;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private TextView tvFile, tvCover, tvQuality, tvEstimate, tvLog;
    private TextView tvLogPath, tvSegment;
    private RangeSlider rsSegment;
    private SeekBar sbCover, sbCrf;
    private android.widget.ImageView ivPreview;
    private MaterialButton btnPick, btnGo;
    private Uri pickedUri;
    private String pickedPath;
    private double videoDuration = -1;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final StringBuilder logBuf = new StringBuilder();
    private final SimpleDateFormat ts = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private OutputStreamWriter logWriter;
    private File logFile;

    private final ActivityResultLauncher<Intent> picker =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
            if (r.getResultCode() == RESULT_OK && r.getData() != null) {
                Uri u = r.getData().getData();
                if (u != null) onPicked(u);
            }
        });

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        askPerm();

        tvFile = findViewById(R.id.tv_file);
        tvCover = findViewById(R.id.tv_cover);
        tvQuality = findViewById(R.id.tv_quality);
        tvEstimate = findViewById(R.id.tv_estimate);
        tvLog = findViewById(R.id.tv_log);
        tvSegment = findViewById(R.id.tv_segment);
        rsSegment = findViewById(R.id.rs_segment);
        sbCover = findViewById(R.id.sb_cover);
        sbCrf = findViewById(R.id.sb_crf);
        ivPreview = findViewById(R.id.iv_preview);
        btnPick = findViewById(R.id.btn_pick);
        btnGo = findViewById(R.id.btn_go);

        // 日志区 - 文件直写确保不丢，UI 更新走 Handler
        tvLogPath = findViewById(R.id.tv_log_path);
        initLogFile();
        findViewById(R.id.btn_clear_log).setOnClickListener(v -> clearLog());
        findViewById(R.id.btn_copy_log).setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("log", logBuf.toString()));
            Toast.makeText(this, "日志已复制", Toast.LENGTH_SHORT).show();
        });
        Converter.setLogListener(line -> {
            writeLogFile(line);
            h.post(() -> appendLogUI(line));
        });

        setupSeek();
        loadCfg();
        checkFFmpeg();

        btnPick.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_PICK, MediaStore.Video.Media.EXTERNAL_CONTENT_URI);
            i.setType("video/*");
            picker.launch(i);
        });

        btnGo.setOnClickListener(v -> go());

        findViewById(R.id.btn_help).setOnClickListener(v ->
            new MaterialAlertDialogBuilder(this)
                .setTitle("📖 使用说明")
                .setMessage("1. 从相册选视频 → 2. 选取片段 → 3. 选画质 → 4. 点转换\n\n" +
                    "📤 相册分享两种入口\n" +
                    "● 「调节参数」→ 打开主界面，调参+实时看进度\n" +
                    "● 「后台转换」→ 一键直转，通知栏看进度\n\n" +
                    "✂ 双滑块选取片段，默认全视频\n" +
                    "🎚 画质模式：极速/推荐/高清/无损\n" +
                    "📸 封面时间可拖动预览\n\n" +
                    "📁 输出: DCIM/Live/\n" +
                    "✅ FFmpeg 已内置，无需网络\n" +
                    "🔔 转换中可切后台，通知栏看进度\n" +
                    "📋 下方日志区可查看运行详情")
                .setPositiveButton("OK", null).show());

        if (getIntent() != null && Intent.ACTION_SEND.equals(getIntent().getAction())) {
            Uri u = getIntent().getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) onPicked(u);
        }

        // 首次启动弹窗
        if (!getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("guide_shown", false)) {
            h.postDelayed(this::showFirstGuide, 800);
        }
    }

    private void showFirstGuide() {
        // CheckBox
        final boolean[] dontShow = {false};
        android.widget.CheckBox cb = new android.widget.CheckBox(this);
        cb.setText("不再提示");
        cb.setTextColor(0xFF888888);
        cb.setPadding(0, 24, 0, 0);
        cb.setOnCheckedChangeListener((btn, checked) -> dontShow[0] = checked);

        String msg = "🎚 画质模式 4 档可选\n" +
            "⚡极速 → 👍推荐 → 🎯高清 → 💎无损\n" +
            "默认为推荐，平衡速度与质量\n\n" +
            "📤 相册分享两种入口\n" +
            "● 调节参数 → 打开主界面调参后转换\n" +
            "● 后台转换 → 一键直转，通知栏看进度\n\n" +
            "💡 提示\n" +
            "● 转换时请保持 App 在前台\n" +
            "● 输出: DCIM/Live/\n" +
            "● 小米相册长按可播放动态照片";

        new MaterialAlertDialogBuilder(this)
            .setTitle("📸 欢迎使用视频转Live")
            .setMessage(msg)
            .setView(cb)
            .setPositiveButton("开始使用", (d, w) -> {
                if (dontShow[0])
                    getSharedPreferences("cfg", MODE_PRIVATE).edit().putBoolean("guide_shown", true).apply();
            })
            .setCancelable(false)
            .show();
    }

    // ---------- 日志 ----------

    private void initLogFile() {
        try {
            File logDir = new File(getFilesDir(), "logs");
            logDir.mkdirs();
            String name = "vtl_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".log";
            logFile = new File(logDir, name);
            logWriter = new OutputStreamWriter(new FileOutputStream(logFile), StandardCharsets.UTF_8);
            tvLogPath.setText("📁 " + logFile.getAbsolutePath());
        } catch (Exception e) {
            tvLogPath.setText("⚠ 日志文件创建失败");
        }
    }

    /** 线程安全写文件 — 在任何线程直接调用，立即 flush */
    private synchronized void writeLogFile(String msg) {
        if (logWriter == null) return;
        try {
            logWriter.write(ts.format(new Date()) + " " + msg + "\n");
            logWriter.flush();
        } catch (Exception ignored) {}
    }

    /** UI 更新 — 必须在主线程调用 */
    private void appendLogUI(String msg) {
        logBuf.append(ts.format(new Date())).append(" ").append(msg).append("\n");
        tvLog.setText(logBuf.toString());
        // 强制滚动到底部
        tvLog.postDelayed(() -> {
            View parent = (View) tvLog.getParent();
            if (parent instanceof ScrollView) {
                ((ScrollView) parent).fullScroll(View.FOCUS_DOWN);
            }
        }, 100);
    }

    /** 主线程快捷方法：同时写文件 + 更新 UI */
    private void logBoth(String msg) {
        writeLogFile(msg);
        appendLogUI(msg);
    }

    private void clearLog() {
        logBuf.setLength(0);
        tvLog.setText("");
        writeLogFile("日志已清空");
        appendLogUI("日志已清空");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (logWriter != null) {
            try { logWriter.close(); } catch (Exception ignored) {}
        }
    }

    // ---------- SeekBar ----------

    private static final String[] QUALITY_NAMES = {"⚡极速", "👍推荐", "🎯高清", "💎无损"};
    private static final String[] QUALITY_DESC = {"秒级·小文件", "均衡·默认", "高画质", "最佳画质"};
    // 预估时间系数 (×视频秒数)
    private static final double[] QUALITY_SPEED = {0.1, 0.5, 1.5, 3.0};

    private void setupSeek() {
        sbCover.setMax(30);
        sbCrf.setMax(3);
        sbCrf.setProgress(1); // 默认推荐

        rsSegment.setOnRangeChangeListener((left, right) -> {
            updateSegmentLabel();
            if (videoDuration > 0) {
                double dur = (right - left) * videoDuration;
                sbCover.setMax(Math.max(1, (int)(dur / 0.1)));
                if (sbCover.getProgress() > sbCover.getMax()) sbCover.setProgress(sbCover.getMax());
            }
            updateEstimate();
            schedulePreview();
        });

        sbCover.setOnSeekBarChangeListener(new SimpleSeek(v -> {
            tvCover.setText(String.format("%.1f秒", v * 0.1));
            schedulePreview();
        }));
        sbCrf.setOnSeekBarChangeListener(new SimpleSeek(v -> {
            tvQuality.setText(QUALITY_NAMES[v] + " · " + QUALITY_DESC[v]);
            updateEstimate();
        }));
    }

    private void updateEstimate() {
        if (videoDuration <= 0) { tvEstimate.setText(""); return; }
        double durSec = (rsSegment.getRightValue() - rsSegment.getLeftValue()) * videoDuration;
        double est = durSec * QUALITY_SPEED[sbCrf.getProgress()];
        if (est < 1) tvEstimate.setText(String.format("预估: <1秒"));
        else if (est < 60) tvEstimate.setText(String.format("预估: ~%d秒", (int)est));
        else tvEstimate.setText(String.format("预估: ~%d分%d秒", (int)est/60, (int)est%60));
    }

    // ---------- 预览 + 片段标签 ----------

    private void updateSegmentLabel() {
        if (videoDuration <= 0) { tvSegment.setText(""); return; }
        double start = rsSegment.getLeftValue() * videoDuration;
        double end = rsSegment.getRightValue() * videoDuration;
        tvSegment.setText(String.format("%.1fs ~ %.1fs", start, end));
    }

    private final Runnable previewTask = new Runnable() {
        public void run() {
            if (pickedPath == null && pickedUri == null) return;
            double start = videoDuration > 0 ? rsSegment.getLeftValue() * videoDuration : 0;
            double t = start + sbCover.getProgress() * 0.1; // start + cover
            try {
                android.media.MediaMetadataRetriever mmr = new android.media.MediaMetadataRetriever();
                if (pickedPath != null) mmr.setDataSource(pickedPath);
                else mmr.setDataSource(MainActivity.this, pickedUri);
                android.graphics.Bitmap bmp = mmr.getFrameAtTime(
                    (long)(t * 1_000_000),
                    android.media.MediaMetadataRetriever.OPTION_CLOSEST);
                mmr.release();
                if (bmp != null) {
                    h.post(() -> ivPreview.setImageBitmap(bmp));
                }
            } catch (Exception ignored) {}
        }
    };

    private void schedulePreview() {
        h.removeCallbacks(previewTask);
        h.postDelayed(previewTask, 400); // 400ms 防抖
    }

    // ---------- 选择视频 ----------

    private void onPicked(Uri u) {
        pickedUri = u;
        pickedPath = UriHelper.getPath(this, u);
        logBoth("已选择视频: " + (pickedPath != null ? pickedPath : u.toString()));

        videoDuration = probeVideoDuration();
        if (videoDuration > 0) {
            rsSegment.setValues(0f, 1f); // 默认全选
        }

        String name = pickedPath != null ? new java.io.File(pickedPath).getName() : "已选视频";
        String info = "📹 " + name;
        if (videoDuration > 0) {
            int min = (int)(videoDuration / 60);
            int sec = (int)(videoDuration % 60);
            info += String.format("  (%d:%02d)", min, sec);
        }
        tvFile.setText(info);
        btnGo.setEnabled(true);
        btnGo.setText("🚀 开始转换");
        updateSegmentLabel();
        updateEstimate();
        schedulePreview();
    }

    private double getFileSizeMB() {
        if (pickedPath != null) {
            long len = new java.io.File(pickedPath).length();
            return len / (1024.0 * 1024.0);
        }
        return 50; // URI 无法获取大小时默认假设中等文件
    }

    private int calcAutoCrf() {
        double mb = getFileSizeMB();
        // ultrafast 预设压缩率低，CRF 要比 normal 高 3-4 才同体积
        if (mb < 10) return 23;       // 小文件
        else if (mb < 50) return 25;
        else if (mb < 200) return 27; // 中等文件
        else if (mb < 500) return 29;
        else return 31;                // 大文件优先小体积
    }

    private double probeVideoDuration() {
        if (pickedPath != null) {
            double d = Converter.probeDuration(pickedPath);
            if (d > 0) return d;
        }
        if (pickedUri != null) {
            double d = Converter.probeDuration(this, pickedUri);
            if (d > 0) return d;
        }
        logBoth("⚠ 无法探测视频时长");
        return -1;
    }

    // ---------- 转换 ----------

    private void go() {
        if (pickedUri == null && pickedPath == null) { Toast.makeText(this, "先选视频", Toast.LENGTH_SHORT).show(); return; }
        btnGo.setEnabled(false);
        btnGo.setText("⏳ 转换中…");
        logBoth("======== 开始转换 ========");

        // 启动前台服务，最小化到后台
        String fName = pickedPath != null
            ? new java.io.File(pickedPath).getName()
            : "视频";
        Intent si = new Intent(this, ConvertService.class);
        si.putExtra("file", fName);
        startService(si);
        ConvertService.update("🔄 转换中", "", 0);
        Toast.makeText(this, "已开始转换，请查看通知栏进度", Toast.LENGTH_SHORT).show();

        Converter.Options o = new Converter.Options();
        o.startSec = rsSegment.getLeftValue() * videoDuration;
        o.maxSec = (rsSegment.getRightValue() - rsSegment.getLeftValue()) * videoDuration;
        o.coverSec = sbCover.getProgress() * 0.1;
        o.quality = sbCrf.getProgress();
        Converter.applyQuality(o);
        saveCfg(o);

        Converter.Callback cb = new Converter.Callback() {
            public void onProgress(String m, int p) {
                h.post(() -> {
                    btnGo.setText(m);
                    if (p < 0) { // GPU 不可用 → CPU 慢速
                        new MaterialAlertDialogBuilder(MainActivity.this)
                            .setTitle("⚡ GPU 不可用")
                            .setMessage("当前视频 GPU 硬编失败（如杜比视界）\n将使用 CPU 编码，速度较慢\n\n长视频建议用「剪映」转换")
                            .setPositiveButton("知道了", null)
                            .setNeutralButton("打开剪映", (dd, ww) -> {
                                Intent i = getPackageManager().getLaunchIntentForPackage("com.lemon.lv");
                                if (i != null) startActivity(i);
                                else try {
                                    startActivity(new Intent(Intent.ACTION_VIEW,
                                        Uri.parse("market://details?id=com.lemon.lv")));
                                } catch (Exception ex) {
                                    Toast.makeText(MainActivity.this, "未安装剪映", Toast.LENGTH_SHORT).show();
                                }
                            })
                            .show();
                    }
                });
                ConvertService.update("🔄 转换中", m, Math.max(p, 0));
            }
            public void onSuccess(String path) { h.post(() -> {
                btnGo.setEnabled(true); btnGo.setText("🚀 开始转换");
                logBoth("✅ 成功: " + path);
                ConvertService.finish("✅ 转换完成", path, true);
                // 通知相册刷新（同时用两种方式确保生效）
                android.media.MediaScannerConnection.scanFile(MainActivity.this,
                    new String[]{path}, null, null);
                sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,
                    Uri.fromFile(new java.io.File(path))));
                new MaterialAlertDialogBuilder(MainActivity.this)
                    .setTitle("✅ 成功").setMessage(path)
                    .setPositiveButton("打开相册", (d,w) -> {
                        // ACTION_PICK 确保唤起相册而非其他看图App
                        try {
                            startActivity(new Intent(Intent.ACTION_PICK,
                                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI));
                        } catch (Exception e) {
                            startActivity(new Intent(Intent.ACTION_VIEW)
                                .setDataAndType(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*"));
                        }
                    }).setNegativeButton("OK", null).show();
            });}
            public void onError(String e) { h.post(() -> {
                btnGo.setEnabled(true); btnGo.setText("🚀 开始转换");
                logBoth("❌ 失败: " + e);
                ConvertService.finish("❌ 转换失败", e, false);
                Toast.makeText(MainActivity.this, "❌ " + e, Toast.LENGTH_LONG).show();
            });}
        };

        if (pickedPath != null) Converter.fromFile(pickedPath, o, cb);
        else Converter.fromUri(this, pickedUri, o, cb);
    }

    // ---------- 配置 ----------

    private void saveCfg(Converter.Options o) {
        getSharedPreferences("cfg", MODE_PRIVATE).edit()
            .putFloat("cover", (float) o.coverSec)
            .putInt("quality", o.quality).apply();
    }

    private void loadCfg() {
        SharedPreferences p = getSharedPreferences("cfg", MODE_PRIVATE);
        float cover = p.getFloat("cover", 0.5f);
        int quality = p.getInt("quality", 1);
        sbCover.setProgress((int) (cover / 0.1));
        sbCrf.setProgress(quality);
        tvCover.setText(String.format("%.1f秒", cover));
        tvQuality.setText(QUALITY_NAMES[quality] + " · " + QUALITY_DESC[quality]);
        tvSegment.setText("选视频后设置");
    }

    // ---------- FFmpeg ----------

    private void askPerm() {
        String perm = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_VIDEO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this, new String[]{perm}, 1);
        // Android 13+ 通知权限：必须弹，否则通知不显示
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                new MaterialAlertDialogBuilder(this)
                    .setTitle("需要通知权限")
                    .setMessage("转换进度需要通过通知栏显示\n\n请点击「允许」开启通知权限")
                    .setPositiveButton("允许", (d, w) ->
                        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2))
                    .setNegativeButton("稍后", null)
                    .show();
            }
        }
    }

    private void checkFFmpeg() {
        logBoth("检查 FFmpeg…");

        if (FFmpegProvider.extractFromAssets(this)) {
            String ffmpeg = FFmpegProvider.getFfmpegPath(this);
            if (ffmpeg != null) {
                Converter.setFfmpegPath(ffmpeg);
                logBoth("FFmpeg: " + ffmpeg);
            }
            String ffprobe = FFmpegProvider.getFfprobePath(this);
            if (ffprobe != null) {
                Converter.setFfprobePath(ffprobe);
                logBoth("ffprobe: " + ffprobe);
            }
            // 探测可用编码器
            h.postDelayed(() -> probeEncoders(), 500);
            return;
        }

        String ffmpeg = FFmpegProvider.getFfmpegPath(this);
        String ffprobe = FFmpegProvider.getFfprobePath(this);
        if (ffmpeg != null) {
            Converter.setFfmpegPath(ffmpeg);
            if (ffprobe != null) Converter.setFfprobePath(ffprobe);
            logBoth("FFmpeg(系统): " + ffmpeg);
            return;
        }

        logBoth("⚠ 未找到 FFmpeg，需要下载");
        new MaterialAlertDialogBuilder(this)
            .setTitle("需要 FFmpeg")
            .setMessage("本应用需要 FFmpeg 才能转换视频。\n\n是否自动下载？（约 30MB）\n\n如网络不畅，可安装 Termux 并运行:\npkg install ffmpeg")
            .setPositiveButton("下载", (d, w) -> {
                findViewById(R.id.btn_pick).setEnabled(false);
                Toast.makeText(this, "正在下载 FFmpeg…", Toast.LENGTH_LONG).show();
                logBoth("开始下载 FFmpeg…");
                FFmpegProvider.downloadAsync(this, new FFmpegProvider.Callback() {
                    public void onProgress(int pct) {}
                    public void onSuccess(String p) {
                        h.post(() -> {
                            Converter.setFfmpegPath(p);
                            findViewById(R.id.btn_pick).setEnabled(true);
                            logBoth("FFmpeg 下载完成: " + p);
                            Toast.makeText(MainActivity.this, "✅ FFmpeg 就绪", Toast.LENGTH_SHORT).show();
                        });
                    }
                    public void onError(String e) {
                        h.post(() -> {
                            logBoth("❌ FFmpeg 下载失败: " + e);
                            Toast.makeText(MainActivity.this, "❌ " + e, Toast.LENGTH_LONG).show();
                        });
                    }
                });
            })
            .setNegativeButton("取消", null)
            .setCancelable(false)
            .show();
    }

    private void probeEncoders() {
        new Thread(() -> {
            final String out = Converter.runFfmpegCmd("-encoders");
            final StringBuilder found = new StringBuilder();
            for (String line : out.split("\n")) {
                String lower = line.trim().toLowerCase();
                if ((lower.contains("h264") || lower.contains("x264") || lower.contains("hevc") || lower.contains("mpeg4"))
                    && lower.matches("^\\s*v.*")) {
                    if (found.length() > 0) found.append(", ");
                    // 取编码器名（第2列）
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 2) found.append(parts[1]);
                }
            }
            final String summary = found.toString();
            h.post(() -> logBoth("可用编码器: " + (summary.isEmpty() ? "无" : summary)));
        }).start();
    }

    static class SimpleSeek implements SeekBar.OnSeekBarChangeListener {
        interface L { void v(int v); }
        private final L l;
        SimpleSeek(L l) { this.l = l; }
        public void onProgressChanged(SeekBar s, int v, boolean b) { l.v(v); }
        public void onStartTrackingTouch(SeekBar s) {}
        public void onStopTrackingTouch(SeekBar s) {}
    }
}
