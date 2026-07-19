package com.fm.centinelaip;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

final class EventStore {
    private static final String INDEX_FILE = "events.json";
    private static final int MAX_EVENTS = 100;
    private final Context context;
    private final File directory;

    EventStore(Context context) {
        this.context = context.getApplicationContext();
        directory = new File(this.context.getFilesDir(), "detection_events");
        if (!directory.exists()) directory.mkdirs();
    }

    synchronized EventRecord save(Bitmap frame, List<Detection> detections, String cameraName) throws Exception {
        long timestamp = System.currentTimeMillis();
        String id = UUID.randomUUID().toString();
        String imageName = id + ".jpg";
        File imageFile = new File(directory, imageName);

        Bitmap annotated = annotate(frame, detections);
        try (FileOutputStream output = new FileOutputStream(imageFile)) {
            if (!annotated.compress(Bitmap.CompressFormat.JPEG, 88, output)) {
                throw new IllegalStateException("No fue posible guardar la captura del evento");
            }
        } finally {
            if (annotated != frame) annotated.recycle();
        }

        EventRecord record = new EventRecord(id, timestamp, cameraName, summarize(detections), imageName);
        List<EventRecord> records = load();
        records.add(0, record);
        while (records.size() > MAX_EVENTS) {
            EventRecord removed = records.remove(records.size() - 1);
            new File(directory, removed.imageFile).delete();
        }
        writeIndex(records);
        return record;
    }

    synchronized List<EventRecord> load() {
        List<EventRecord> records = new ArrayList<>();
        File index = new File(directory, INDEX_FILE);
        if (!index.exists()) return records;
        try (FileInputStream input = new FileInputStream(index);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            JSONArray array = new JSONArray(output.toString(StandardCharsets.UTF_8.name()));
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                String image = item.optString("image", "");
                if (image.isEmpty() || !new File(directory, image).exists()) continue;
                records.add(new EventRecord(
                        item.optString("id"),
                        item.optLong("timestamp"),
                        item.optString("camera", "Cámara IP"),
                        item.optString("summary", "Objeto detectado"),
                        image));
            }
        } catch (Exception ignored) {
            // Un índice incompleto no debe impedir abrir la vista en vivo.
        }
        return records;
    }

    File imageFile(EventRecord record) {
        return new File(directory, record.imageFile);
    }

    synchronized void clear() {
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) file.delete();
        }
    }

    private void writeIndex(List<EventRecord> records) throws Exception {
        JSONArray array = new JSONArray();
        for (EventRecord record : records) {
            JSONObject item = new JSONObject();
            item.put("id", record.id);
            item.put("timestamp", record.timestamp);
            item.put("camera", record.cameraName);
            item.put("summary", record.summary);
            item.put("image", record.imageFile);
            array.put(item);
        }
        File temporary = new File(directory, INDEX_FILE + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(array.toString().getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        File destination = new File(directory, INDEX_FILE);
        if (destination.exists()) destination.delete();
        if (!temporary.renameTo(destination)) throw new IllegalStateException("No fue posible actualizar eventos");
    }

    private static Bitmap annotate(Bitmap frame, List<Detection> detections) {
        Bitmap result = frame.copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas = new Canvas(result);
        float densityScale = Math.max(1f, result.getWidth() / 720f);
        Paint box = new Paint(Paint.ANTI_ALIAS_FLAG);
        box.setStyle(Paint.Style.STROKE);
        box.setStrokeWidth(3f * densityScale);
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(Color.WHITE);
        text.setFakeBoldText(true);
        text.setTextSize(18f * densityScale);
        Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);

        for (Detection detection : detections) {
            box.setColor(detection.color);
            canvas.drawRoundRect(new RectF(detection.left, detection.top, detection.right, detection.bottom),
                    6f, 6f, box);
            String value = detection.label + " " + Math.round(detection.confidence * 100) + "%";
            float width = text.measureText(value) + 16f * densityScale;
            float height = 28f * densityScale;
            float top = Math.max(0f, detection.top - height);
            label.setColor(detection.color);
            canvas.drawRect(detection.left, top, detection.left + width, top + height, label);
            canvas.drawText(value, detection.left + 8f * densityScale, top + 20f * densityScale, text);
        }
        return result;
    }

    private static String summarize(List<Detection> detections) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Detection detection : detections) counts.merge(detection.label, 1, Integer::sum);
        StringBuilder summary = new StringBuilder();
        for (Map.Entry<String, Integer> item : counts.entrySet()) {
            if (summary.length() > 0) summary.append(" · ");
            summary.append(item.getValue()).append(" ").append(item.getKey());
            if (counts.size() > 3 && summary.length() > 42) break;
        }
        return summary.length() == 0 ? "Movimiento detectado" : summary.toString();
    }
}
