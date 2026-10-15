package com.puter.unofficial;

import android.content.Context;
import android.content.res.AssetManager;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * Utility class to read local asset files.
 * This bypasses WebView CORS restrictions that prevent 'fetch' from working
 * on local file:/// systems.
 */
public class AssetUtils {

    /**
     * Reads a file from the assets folder and returns its content as a String.
     * 
     * @param context  The application context.
     * @param fileName The name of the file in the assets folder (e.g., "models.json").
     * @return The file content as a String, or an empty JSON object "{}" on error.
     */
    public static String readFile(Context context, String fileName) {
        StringBuilder stringBuilder = new StringBuilder();
        InputStream inputStream = null;
        BufferedReader bufferedReader = null;

        try {
            AssetManager assetManager = context.getAssets();
            inputStream = assetManager.open(fileName);
            bufferedReader = new BufferedReader(new InputStreamReader(inputStream, "UTF-8"));

            String line;
            while ((line = bufferedReader.readLine()) != null) {
                stringBuilder.append(line);
            }
        } catch (IOException e) {
            e.printStackTrace();
            return "{}"; // Return empty JSON object to prevent JS crashes
        } finally {
            try {
                if (bufferedReader != null) {
                    bufferedReader.close();
                }
                if (inputStream != null) {
                    inputStream.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return stringBuilder.toString();
    }
}