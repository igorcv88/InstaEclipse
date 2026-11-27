package ps.reso.instaeclipse.library.android;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;

import ps.reso.instaeclipse.library.core.MediaLibrary;

import java.io.File;

/** Device decode check run before a file is accepted (after structural validation). */
public final class AndroidDecodeProbe implements MediaLibrary.DecodeProbe {
    @Override
    public String check(File file, String mimeType) {
        if (mimeType.startsWith("image/")) {
            return decodeImage(file);
        }
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getPath());
            String hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO);
            String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (!"yes".equals(hasVideo)) return "no_video_track";
            return duration != null && Long.parseLong(duration) > 0 ? null : "no_duration";
        } catch (RuntimeException failure) {
            return "video_undecodable";
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    private static String decodeImage(File file) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), options);
        if (options.outWidth <= 0 || options.outHeight <= 0) return "image_undecodable";
        int largestDimension = Math.max(options.outWidth, options.outHeight);
        options.inSampleSize = 1;
        while (largestDimension / options.inSampleSize > 512) options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        Bitmap decoded = BitmapFactory.decodeFile(file.getPath(), options);
        if (decoded == null) return "image_undecodable";
        decoded.recycle();
        return null;
    }

}
