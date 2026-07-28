package com.videotolive.app;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.text.TextUtils;
import java.io.File;

public class UriHelper {

    public static String getPath(Context context, Uri uri) {
        if (uri == null) return null;
        if ("file".equals(uri.getScheme())) return uri.getPath();
        if ("content".equals(uri.getScheme())) return fromContent(context, uri);
        return null;
    }

    private static String fromContent(Context context, Uri uri) {
        ContentResolver cr = context.getContentResolver();
        String[] proj = {MediaStore.Video.Media.DATA};
        Cursor c = null;
        try {
            c = cr.query(uri, proj, null, null, null);
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATA);
                String p = c.getString(i);
                if (!TextUtils.isEmpty(p) && new File(p).exists()) return p;
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return null;
    }
}
