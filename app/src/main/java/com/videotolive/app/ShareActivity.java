package com.videotolive.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;

/**
 * 分享接收 — 相册分享视频到这里，自动转换
 * 默认使用原视频完整时长
 */
public class ShareActivity extends AppCompatActivity {

    private View vProg, vOk, vErr;
    private ProgressBar pb;
    private TextView tvProg, tvOk, tvErr;
    private final Handler h = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_share);

        vProg = findViewById(R.id.v_prog);
        vOk = findViewById(R.id.v_ok);
        vErr = findViewById(R.id.v_err);
        pb = findViewById(R.id.pb);
        tvProg = findViewById(R.id.tv_prog);
        tvOk = findViewById(R.id.tv_ok);
        tvErr = findViewById(R.id.tv_err);

        findViewById(R.id.btn_close).setOnClickListener(v -> finish());
        findViewById(R.id.btn_close2).setOnClickListener(v -> finish());
        findViewById(R.id.btn_gallery).setOnClickListener(v -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW).setType("image/*")); } catch (Exception ignored) {}
            finish();
        });

        ensureFFmpeg();
        handle(getIntent());
    }

    private void ensureFFmpeg() {
        FFmpegProvider.extractFromAssets(this);
        String ffmpeg = FFmpegProvider.getFfmpegPath(this);
        if (ffmpeg != null) Converter.setFfmpegPath(ffmpeg);
        String ffprobe = FFmpegProvider.getFfprobePath(this);
        if (ffprobe != null) Converter.setFfprobePath(ffprobe);
    }

    private void handle(Intent i) {
        if (i == null || !Intent.ACTION_SEND.equals(i.getAction())) { err("不支持"); return; }
        Uri u = i.getParcelableExtra(Intent.EXTRA_STREAM);
        if (u == null) { err("无视频"); return; }

        show(vProg); pb.setProgress(0); tvProg.setText("准备…");

        // 前台服务
        Intent si = new Intent(this, ConvertService.class);
        si.putExtra("file", "快捷转换");
        startService(si);
        ConvertService.update("🔄 转换中", "", 0);
        Toast.makeText(this, "已开始转换，请查看通知栏进度", Toast.LENGTH_SHORT).show();
        moveTaskToBack(true);

        Converter.Options o = new Converter.Options();
        try {
            SharedPreferences sp = getSharedPreferences("cfg", MODE_PRIVATE);
            o.coverSec = sp.getFloat("cover", 0.5f);
            o.quality = sp.getInt("quality", 1);
            Converter.applyQuality(o);

            // 尝试获取原视频时长作为默认值
            double dur = Converter.probeDuration(this, u);
            if (dur > 0) {
                o.maxSec = dur;
            } else {
                o.maxSec = sp.getFloat("dur", 3.9f);
            }
        } catch (Exception ignored) {
            // fallback: probe in Converter
        }

        Converter.fromUri(this, u, o, new Converter.Callback() {
            public void onProgress(String m, int p) {
                h.post(() -> {
                    tvProg.setText(m);
                    pb.setProgress(Math.max(p, 0));
                    if (p < 0) {
                        Toast.makeText(ShareActivity.this,
                            "GPU 不可用，CPU 编码较慢", Toast.LENGTH_LONG).show();
                    }
                });
                ConvertService.update("🔄 转换中", m, Math.max(p, 0));
            }
            public void onSuccess(String path) { h.post(() -> {
                File f = new File(path);
                show(vOk);
                tvOk.setText("✅ 成功\n\n" + path + "\n\n📦 " + (f.length()/1024) + " KB\n\n在小米相册中长按可播放");
                ConvertService.finish("✅ 转换完成", path, true);
                android.media.MediaScannerConnection.scanFile(ShareActivity.this,
                    new String[]{path}, null, null);
            });}
            public void onError(String e) {
                h.post(() -> err(e));
                ConvertService.finish("❌ 转换失败", e, false);
            }
        });
    }

    private void show(View v) {
        vProg.setVisibility(v == vProg ? View.VISIBLE : View.GONE);
        vOk.setVisibility(v == vOk ? View.VISIBLE : View.GONE);
        vErr.setVisibility(v == vErr ? View.VISIBLE : View.GONE);
    }

    private void err(String m) { show(vErr); tvErr.setText("❌ " + m); }
}
