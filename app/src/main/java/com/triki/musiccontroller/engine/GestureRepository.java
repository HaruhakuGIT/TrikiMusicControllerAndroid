package com.triki.musiccontroller.engine;

import android.content.Context;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.triki.musiccontroller.model.RecordedGesture;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class GestureRepository {

    private static final String TAG = "GestureRepository";
    private final File gesturesDir;
    private final Gson gson;

    public GestureRepository(Context context) {
        this.gesturesDir = new File(context.getFilesDir(), "gestures");
        if (!gesturesDir.exists()) {
            boolean created = gesturesDir.mkdirs();
            Log.d(TAG, "Created gestures dir: " + created);
        }
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        ensureDefaultGestures(context);
    }

    public synchronized void ensureDefaultGestures(Context context) {
        File[] existing = gesturesDir.listFiles((dir, name) -> name.endsWith(".json"));
        if (existing != null && existing.length > 0) {
            return; // Already initialized with gestures
        }

        try {
            String[] assets = context.getAssets().list("gestures");
            if (assets == null || assets.length == 0) return;

            for (String file : assets) {
                if (!file.endsWith(".json")) continue;
                try (InputStream in = context.getAssets().open("gestures/" + file);
                     OutputStream out = new FileOutputStream(new File(gesturesDir, file))) {
                    byte[] buf = new byte[4096];
                    int len;
                    while ((len = in.read(buf)) > 0) {
                        out.write(buf, 0, len);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed copying asset gesture: " + file, e);
                }
            }
            Log.d(TAG, "Initialized default gestures from assets to internal storage");
        } catch (Exception e) {
            Log.e(TAG, "Error listing asset gestures", e);
        }
    }

    public synchronized void restoreDefaultGestures(Context context) {
        clearAll();
        try {
            String[] assets = context.getAssets().list("gestures");
            if (assets != null) {
                for (String file : assets) {
                    if (!file.endsWith(".json")) continue;
                    try (InputStream in = context.getAssets().open("gestures/" + file);
                         OutputStream out = new FileOutputStream(new File(gesturesDir, file))) {
                        byte[] buf = new byte[4096];
                        int len;
                        while ((len = in.read(buf)) > 0) {
                            out.write(buf, 0, len);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error restoring default gestures", e);
        }
    }

    public synchronized String saveRecording(RecordedGesture gesture) {
        String actionPrefix = gesture.getMusicAction() != null ? gesture.getMusicAction().name() : "Gesture";
        String timeStr = new SimpleDateFormat("yyyyMMdd_HHmmssSSS", Locale.US).format(new Date());
        String shortUid = UUID.randomUUID().toString().substring(0, 4);
        String fileName = actionPrefix + "_" + timeStr + "_" + shortUid + ".json";
        File file = new File(gesturesDir, fileName);

        try (Writer writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            gson.toJson(gesture, writer);
            gesture.fileName = fileName;
            gesture.filePath = file.getAbsolutePath();
            Log.d(TAG, "Saved gesture: " + fileName);
            return fileName;
        } catch (Exception e) {
            Log.e(TAG, "Failed to save gesture " + fileName, e);
            return null;
        }
    }

    public synchronized List<RecordedGesture> loadAllRecordings() {
        List<RecordedGesture> list = new ArrayList<>();
        File[] files = gesturesDir.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null || files.length == 0) return list;

        for (File f : files) {
            try (InputStreamReader reader = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
                RecordedGesture g = gson.fromJson(reader, RecordedGesture.class);
                if (g != null) {
                    g.fileName = f.getName();
                    g.filePath = f.getAbsolutePath();
                    list.add(g);
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to read gesture file: " + f.getName(), e);
            }
        }

        // Sort: newest first
        Collections.sort(list, (a, b) -> {
            String ta = a.recordedAt != null ? a.recordedAt : a.fileName;
            String tb = b.recordedAt != null ? b.recordedAt : b.fileName;
            return tb.compareTo(ta);
        });

        return list;
    }

    public synchronized boolean deleteRecording(String fileName) {
        if (fileName == null || fileName.isEmpty()) return false;
        File f = new File(gesturesDir, fileName);
        if (f.exists()) {
            boolean deleted = f.delete();
            Log.d(TAG, "Deleted individual gesture file " + fileName + ": " + deleted);
            return deleted;
        }
        return false;
    }

    public synchronized void clearAll() {
        File[] files = gesturesDir.listFiles((dir, name) -> name.endsWith(".json"));
        if (files != null) {
            for (File f : files) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        Log.d(TAG, "Cleared all gesture recordings");
    }

    public File getGesturesDir() {
        return gesturesDir;
    }
}
