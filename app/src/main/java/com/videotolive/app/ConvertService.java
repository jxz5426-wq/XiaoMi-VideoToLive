package com.videotolive.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.provider.MediaStore;

/**
 * 前台服务：通知栏显示转换进度，完成/失败通知可交互
 */
public class ConvertService extends Service {

    private static final int NOTIFY_ID = 1001;
    private static final String CHANNEL_ID = "convert_channel_v2";
    private static ConvertService sInstance;
    private String fileName = "";

    public static void update(String title, String text, int pct) {
        if (sInstance != null) sInstance.showProgress(title, text, pct);
    }

    public static void finish(String title, String path, boolean success) {
        if (sInstance != null) {
            sInstance.showResult(title, path, success);
            sInstance.stopForeground(STOP_FOREGROUND_DETACH);
            sInstance.stopSelf();
            sInstance = null;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra("file")) {
            fileName = intent.getStringExtra("file");
        }
        showProgress("🔄 准备转换", fileName, 0);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() { sInstance = null; super.onDestroy(); }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "转换进度", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("视频转Live转换状态");
            ch.setShowBadge(false);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
    }

    private PendingIntent openApp() {
        Intent i = new Intent(this, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, i,
            PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
    }

    private PendingIntent openGallery() {
        Intent i = new Intent(Intent.ACTION_PICK,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        return PendingIntent.getActivity(this, 1, i,
            PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
    }

    private PendingIntent openFile(String path) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(Uri.parse("file://" + path), "image/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return PendingIntent.getActivity(this, 1, i,
            PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
    }

    private boolean firstShow = true;

    private void showProgress(String title, String text, int pct) {
        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text.isEmpty() ? fileName : text)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentIntent(openApp())
            .setOngoing(true)
            .setPriority(Notification.PRIORITY_DEFAULT)
            .setDefaults(firstShow ? Notification.DEFAULT_VIBRATE : 0)
            .setProgress(100, pct, pct <= 0);
        if (!fileName.isEmpty() && !text.equals(fileName)) {
            b.setSubText(fileName);
        }
        Notification n = b.build();
        // 首次先用 notify() 弹横幅，再绑定前台服务
        if (firstShow) {
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFY_ID, n);
            firstShow = false;
        }
        startForeground(NOTIFY_ID, n);
    }

    private void showResult(String title, String path, boolean success) {
        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(success ? "点击打开相册" : path)
            .setSmallIcon(success ? android.R.drawable.ic_menu_gallery : android.R.drawable.ic_dialog_alert)
            .setAutoCancel(true)
            .setOngoing(false);

        if (success && path != null && !path.isEmpty()) {
            b.setContentIntent(openGallery());
            // 添加"打开相册"按钮
            b.addAction(new Notification.Action.Builder(
                android.R.drawable.ic_menu_gallery, "打开相册", openGallery()).build());
        } else {
            b.setContentIntent(openApp());
        }

        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFY_ID, b.build());
    }
}
