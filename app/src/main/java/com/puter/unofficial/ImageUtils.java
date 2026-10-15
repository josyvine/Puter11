package com.puter.unofficial;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Optimized Utility class to handle image processing for Puter Vision.
 * Handles resizing to prevent memory crashes and fixes image orientation.
 */
public class ImageUtils {

    private static final String TAG = "PuterImageUtils";
    private static final int MAX_IMAGE_DIMENSION = 1280; // Optimized for AI Vision models

    /**
     * Converts an image URI to a Base64 encoded string.
     * Includes memory-safe decoding and rotation correction.
     */
    public static String uriToBase64(Context context, Uri imageUri) {
        try {
            // 1. Get rotation info from EXIF
            int rotation = getRotation(context, imageUri);

            // 2. Decode with sub-sampling to prevent OutOfMemory crashes
            Bitmap bitmap = decodeSampledBitmapFromUri(context, imageUri, MAX_IMAGE_DIMENSION, MAX_IMAGE_DIMENSION);
            
            if (bitmap == null) return null;

            // 3. Fix orientation if necessary
            if (rotation != 0) {
                bitmap = rotateBitmap(bitmap, rotation);
            }

            return bitmapToBase64(bitmap);
        } catch (Exception e) {
            Log.e(TAG, "Error processing image: " + e.getMessage());
            return null;
        }
    }

    private static Bitmap decodeSampledBitmapFromUri(Context context, Uri uri, int reqWidth, int reqHeight) throws Exception {
        InputStream input = context.getContentResolver().openInputStream(uri);

        // First decode with inJustDecodeBounds=true to check dimensions
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeStream(input, null, options);
        input.close();

        // Calculate inSampleSize
        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);

        // Decode bitmap with inSampleSize set
        input = context.getContentResolver().openInputStream(uri);
        options.inJustDecodeBounds = false;
        Bitmap bitmap = BitmapFactory.decodeStream(input, null, options);
        input.close();

        return bitmap;
    }

    private static int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }

    private static int getRotation(Context context, Uri uri) {
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            ExifInterface exifInterface = new ExifInterface(input);
            int orientation = exifInterface.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90: return 90;
                case ExifInterface.ORIENTATION_ROTATE_180: return 180;
                case ExifInterface.ORIENTATION_ROTATE_270: return 270;
                default: return 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    private static Bitmap rotateBitmap(Bitmap bitmap, int degrees) {
        Matrix matrix = new Matrix();
        matrix.postRotate(degrees);
        Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        bitmap.recycle();
        return rotated;
    }

    private static String bitmapToBase64(Bitmap bitmap) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        // 75% quality is the sweet spot for Vision AI (clear enough, much smaller payload)
        bitmap.compress(Bitmap.CompressFormat.JPEG, 75, outputStream);
        byte[] byteArray = outputStream.toByteArray();
        bitmap.recycle();
        
        String encoded = Base64.encodeToString(byteArray, Base64.NO_WRAP);
        return "data:image/jpeg;base64," + encoded;
    }
}