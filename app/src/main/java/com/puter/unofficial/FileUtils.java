package com.puter.unofficial;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.util.Base64;
import android.util.Log;
import android.webkit.MimeTypeMap;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Refined Utility class to handle general file operations.
 * Converts documents, text files, and other attachments into Data URIs
 * for direct injection into the Puter.js chat logic.
 * 
 * ENHANCEMENT: Optimized to support multi-file staging for Images, TXT, ZIP, and Code files.
 * Requirement: Encodes non-image files into robust Base64 strings for the 'stagedFiles' array.
 */
public class FileUtils {

    private static final String TAG = "PuterFileUtils";

    /**
     * Reads a file and encodes it to a Base64 Data URI.
     * Format: data:[mime/type];base64,[data]
     * 
     * Requirement Fix: This method is now the primary engine for the advanced 
     * file preview UI, handling everything from .jpg to .zip and .java files.
     * 
     * @param context App context.
     * @param fileUri The Uri from the file picker.
     * @return Full Data URI string, or null if an error occurs.
     */
    public static String fileToDataUri(Context context, Uri fileUri) {
        InputStream inputStream = null;
        try {
            ContentResolver contentResolver = context.getContentResolver();
            String mimeType = contentResolver.getType(fileUri);
            
            // Fallback for mime type if content resolver fails
            if (mimeType == null) {
                mimeType = getMimeType(context, fileUri);
            }

            inputStream = contentResolver.openInputStream(fileUri);
            
            /* 
             * Using an 8KB buffer for balanced performance during the encoding 
             * of potentially large files like ZIP archives or long source code.
             */
            byte[] buffer = new byte[8192];
            int bytesRead;
            ByteArrayOutputStream output = new ByteArrayOutputStream();

            while ((bytesRead = inputStream.read(buffer)) != -1) {
                output.write(buffer, 0, bytesRead);
            }

            byte[] fileBytes = output.toByteArray();
            
            /* 
             * NO_WRAP is used to ensure the resulting string is a single continuous 
             * block of text, which is required for valid Data URIs in Puter.js.
             */
            String base64Data = Base64.encodeToString(fileBytes, Base64.NO_WRAP);

            // Return as a standard Data URI so Puter.js knows how to handle it
            // This satisfies the requirement for multi-file content blocks in sendMessage.
            return "data:" + mimeType + ";base64," + base64Data;

        } catch (Exception e) {
            Log.e(TAG, "Failed to convert file to Data URI: " + e.getMessage());
            return null;
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (Exception e) {
                    Log.e(TAG, "Error closing stream: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Helper to resolve MIME type from Uri or File Extension.
     * This is crucial for correctly identifying TXT, Code, and ZIP files 
     * so the AI can process them with the appropriate context.
     */
    public static String getMimeType(Context context, Uri uri) {
        String mimeType;
        if (ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            ContentResolver cr = context.getContentResolver();
            mimeType = cr.getType(uri);
        } else {
            String fileExtension = MimeTypeMap.getFileExtensionFromUrl(uri.toString());
            mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                    fileExtension.toLowerCase());
        }
        
        // Final fallback for unknown file types
        return mimeType != null ? mimeType : "application/octet-stream";
    }

    /**
     * Extracts the readable file name from a Uri.
     * Used by the index.html logic to label thumbnails in the new 
     * Advanced File Preview UI.
     */
    public static String getFileName(Context context, Uri uri) {
        String result = null;
        if (uri.getScheme().equals("content")) {
            android.database.Cursor cursor = context.getContentResolver().query(uri, null, null, null, null);
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (index != -1) {
                        result = cursor.getString(index);
                    }
                }
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }
        if (result == null) {
            result = uri.getPath();
            int cut = result.lastIndexOf('/');
            if (cut != -1) {
                result = result.substring(cut + 1);
            }
        }
        return result;
    }
}