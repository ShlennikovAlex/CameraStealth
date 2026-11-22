package com.example.camerastealth;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;

class MediaSink {

    static final String FOLDER = "Stealth";

    static Uri newVideo(Context ctx, String name) {
        return newEntry(ctx, name, true);
    }

    static Uri newPhoto(Context ctx, String name) {
        return newEntry(ctx, name, false);
    }

    private static Uri newEntry(Context ctx, String name, boolean video) {
        try {
            ContentResolver cr = ctx.getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, video ? "video/mp4" : "image/jpeg");

            if (Build.VERSION.SDK_INT >= 29) {
                v.put(MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DCIM + "/" + FOLDER);
                v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DCIM), FOLDER);
                if (!dir.exists()) dir.mkdirs();
                v.put(MediaStore.MediaColumns.DATA, new File(dir, name).getAbsolutePath());
            }

            return cr.insert(video
                    ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    : MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        } catch (Exception e) {
            return null;
        }
    }

    static void finish(Context ctx, Uri uri) {
        if (uri == null || Build.VERSION.SDK_INT < 29) return;
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.IS_PENDING, 0);
            ctx.getContentResolver().update(uri, v, null, null);
        } catch (Exception ignored) { }
    }
}
