package com.fm.centinelaip;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class DatasetStore {
    private static final String INDEX = "annotations.json";
    private final File directory;
    private final File images;

    DatasetStore(Context context) {
        directory = new File(context.getApplicationContext().getFilesDir(), "training_dataset");
        images = new File(directory, "images");
        if (!images.exists()) images.mkdirs();
    }

    synchronized AnnotationSample save(Bitmap bitmap, String sourceKey, long timestampUs,
                                       List<AnnotationBox> boxes) throws Exception {
        String id = UUID.randomUUID().toString();
        String imageName = id + ".jpg";
        File image = new File(images, imageName);
        try (FileOutputStream output = new FileOutputStream(image)) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 96, output)) {
                throw new IllegalStateException("No se pudo guardar la imagen anotada");
            }
        }
        AnnotationSample sample = new AnnotationSample(id, imageName,
                sourceKey == null ? "unknown" : sourceKey,
                timestampUs, System.currentTimeMillis(), boxes);
        List<AnnotationSample> values = list();
        values.add(0, sample);
        write(values);
        return sample;
    }

    synchronized List<AnnotationSample> list() {
        List<AnnotationSample> result = new ArrayList<>();
        File index = new File(directory, INDEX);
        if (!index.exists()) return result;
        try {
            JSONArray array = new JSONArray(readText(index));
            for (int i = 0; i < array.length(); i++) {
                AnnotationSample sample = AnnotationSample.fromJson(array.getJSONObject(i));
                if (new File(images, sample.imageFile).isFile()) result.add(sample);
            }
        } catch (Exception ignored) { }
        return result;
    }

    File imageFile(AnnotationSample sample) {
        return new File(images, sample.imageFile);
    }

    synchronized AnnotationSample find(String id) {
        if (id == null || id.isEmpty()) return null;
        for (AnnotationSample sample : list()) {
            if (id.equals(sample.id)) return sample;
        }
        return null;
    }

    synchronized boolean updateAnnotations(String id, List<AnnotationBox> boxes) {
        List<AnnotationSample> samples = list();
        for (int i = 0; i < samples.size(); i++) {
            AnnotationSample current = samples.get(i);
            if (!current.id.equals(id)) continue;
            samples.set(i, new AnnotationSample(current.id, current.imageFile,
                    current.sourceKey, current.timestampUs, current.createdAt, boxes));
            write(samples);
            return true;
        }
        return false;
    }

    synchronized boolean deleteSample(String id) {
        List<AnnotationSample> samples = list();
        for (int i = samples.size() - 1; i >= 0; i--) {
            AnnotationSample sample = samples.get(i);
            if (!sample.id.equals(id)) continue;
            File image = imageFile(sample);
            if (image.exists() && !image.delete()) return false;
            samples.remove(i);
            write(samples);
            return true;
        }
        return false;
    }

    /** Elimina cajas rechazadas; una imagen sin cajas también deja de ser una muestra. */
    synchronized PurgeResult purgeRejected() {
        int removedBoxes = 0;
        int removedSamples = 0;
        List<AnnotationSample> samples = list();
        for (int i = samples.size() - 1; i >= 0; i--) {
            AnnotationSample sample = samples.get(i);
            for (int j = sample.boxes.size() - 1; j >= 0; j--) {
                if (AnnotationBox.REJECTED.equals(sample.boxes.get(j).state)) {
                    sample.boxes.remove(j);
                    removedBoxes++;
                }
            }
            if (sample.boxes.isEmpty()) {
                File image = imageFile(sample);
                if (image.exists()) image.delete();
                samples.remove(i);
                removedSamples++;
            }
        }
        write(samples);
        return new PurgeResult(removedBoxes, removedSamples);
    }

    synchronized CurationResult curate(float acceptAt, float rejectBelow) {
        int accepted = 0;
        int review = 0;
        int rejected = 0;
        List<AnnotationSample> samples = list();
        for (AnnotationSample sample : samples) {
            for (AnnotationBox box : sample.boxes) {
                if (AnnotationBox.MANUAL.equals(box.origin)) {
                    box.state = AnnotationBox.ACCEPTED;
                    accepted++;
                } else if (box.confidence >= acceptAt) {
                    box.state = AnnotationBox.ACCEPTED;
                    accepted++;
                } else if (box.confidence < rejectBelow) {
                    box.state = AnnotationBox.REJECTED;
                    rejected++;
                } else {
                    box.state = AnnotationBox.REVIEW;
                    review++;
                }
            }
        }
        write(samples);
        return new CurationResult(accepted, review, rejected);
    }

    synchronized DatasetStats stats() {
        int boxes = 0;
        int ready = 0;
        int review = 0;
        int rejected = 0;
        Set<String> labels = new LinkedHashSet<>();
        List<AnnotationSample> samples = list();
        for (AnnotationSample sample : samples) {
            for (AnnotationBox box : sample.boxes) {
                boxes++;
                labels.add(box.label);
                if (box.isTrainingReady()) ready++;
                else if (AnnotationBox.REVIEW.equals(box.state)) review++;
                else if (AnnotationBox.REJECTED.equals(box.state)) rejected++;
            }
        }
        return new DatasetStats(samples.size(), boxes, ready, review, rejected, labels.size());
    }

    synchronized RescoreResult rescore(YoloDetector detector, float acceptAt, float rejectBelow,
                                       Progress progress) throws Exception {
        List<AnnotationSample> samples = list();
        int accepted = 0, review = 0, rejected = 0;
        for (int sampleIndex = 0; sampleIndex < samples.size(); sampleIndex++) {
            AnnotationSample sample = samples.get(sampleIndex);
            Bitmap bitmap = BitmapFactory.decodeFile(imageFile(sample).getAbsolutePath());
            if (bitmap == null) continue;
            int imageWidth = bitmap.getWidth();
            int imageHeight = bitmap.getHeight();
            List<Detection> detections;
            try {
                detections = detector.detect(bitmap, .05f);
            } finally {
                bitmap.recycle();
            }
            for (AnnotationBox box : sample.boxes) {
                if (AnnotationBox.MANUAL.equals(box.origin)) {
                    box.state = AnnotationBox.ACCEPTED;
                    accepted++;
                    continue;
                }
                float bestIou = 0f, bestConfidence = 0f;
                for (Detection detection : detections) {
                    if (!box.label.equals(detection.label)) continue;
                    AnnotationBox predicted = new AnnotationBox(
                            detection.left / Math.max(1f, imageWidth),
                            detection.top / Math.max(1f, imageHeight),
                            detection.right / Math.max(1f, imageWidth),
                            detection.bottom / Math.max(1f, imageHeight),
                            detection.label, detection.confidence, AnnotationBox.AUTO, AnnotationBox.REVIEW);
                    float overlap = iou(box, predicted);
                    if (overlap > bestIou) { bestIou = overlap; bestConfidence = detection.confidence; }
                }
                box.confidence = bestIou < .10f ? 0f : .55f * bestConfidence + .45f * bestIou;
                if (box.confidence >= acceptAt) { box.state = AnnotationBox.ACCEPTED; accepted++; }
                else if (box.confidence < rejectBelow) { box.state = AnnotationBox.REJECTED; rejected++; }
                else { box.state = AnnotationBox.REVIEW; review++; }
            }
            rejectDuplicates(sample.boxes);
            if (progress != null) progress.onProgress(sampleIndex + 1, samples.size());
        }
        write(samples);
        DatasetStats finalStats = stats();
        return new RescoreResult(finalStats.ready, finalStats.review, finalStats.rejected);
    }

    synchronized ExportResult exportYolo(OutputStream destination) throws Exception {
        List<AnnotationSample> samples = list();
        List<AnnotationSample> eligible = new ArrayList<>();
        Set<String> labelSet = new LinkedHashSet<>();
        for (AnnotationSample sample : samples) {
            boolean hasReadyBox = false;
            for (AnnotationBox box : sample.boxes) {
                if (box.isTrainingReady()) {
                    labelSet.add(box.label);
                    hasReadyBox = true;
                }
            }
            if (hasReadyBox) eligible.add(sample);
        }
        List<String> labels = new ArrayList<>(labelSet);
        Collections.sort(labels);
        if (labels.isEmpty()) throw new IllegalStateException("No hay anotaciones aceptadas para exportar");
        if (eligible.size() < 2) {
            throw new IllegalStateException("Se necesitan al menos 2 muestras aceptadas para train y val");
        }
        Map<String, String> splitById = buildSplits(eligible);

        int train = 0;
        int val = 0;
        try (ZipOutputStream zip = new ZipOutputStream(destination)) {
            for (AnnotationSample sample : eligible) {
                List<AnnotationBox> ready = new ArrayList<>();
                for (AnnotationBox box : sample.boxes) if (box.isTrainingReady()) ready.add(box);
                String split = splitById.get(sample.id);
                if ("val".equals(split)) val++; else train++;
                addFile(zip, "images/" + split + "/" + sample.id + ".jpg", imageFile(sample));

                StringBuilder yolo = new StringBuilder();
                for (AnnotationBox box : ready) {
                    int classId = labels.indexOf(box.label);
                    float width = box.right - box.left;
                    float height = box.bottom - box.top;
                    float centerX = box.left + width / 2f;
                    float centerY = box.top + height / 2f;
                    yolo.append(String.format(Locale.US, "%d %.7f %.7f %.7f %.7f%n",
                            classId, centerX, centerY, width, height));
                }
                addText(zip, "labels/" + split + "/" + sample.id + ".txt", yolo.toString());
            }

            StringBuilder yaml = new StringBuilder();
            yaml.append("path: .\ntrain: images/train\nval: images/val\nnames:\n");
            for (int i = 0; i < labels.size(); i++) {
                yaml.append("  ").append(i).append(": '")
                        .append(labels.get(i).replace("'", "''")).append("'\n");
            }
            addText(zip, "data.yaml", yaml.toString());
            addText(zip, "classes.txt", String.join("\n", labels) + "\n");

            JSONArray raw = new JSONArray();
            for (AnnotationSample sample : samples) raw.put(sample.toJson());
            addText(zip, "annotations.json", raw.toString(2));
            JSONObject manifest = new JSONObject();
            manifest.put("format", "centinela-yolo-dataset-v1");
            manifest.put("train_images", train);
            manifest.put("validation_images", val);
            manifest.put("classes", labels.size());
            addText(zip, "manifest.json", manifest.toString(2));
        }
        return new ExportResult(train, val, labels.size());
    }

    /** Mantiene fuentes distintas separadas; con un solo video usa un bloque temporal final. */
    private static Map<String, String> buildSplits(List<AnnotationSample> samples) {
        Map<String, List<AnnotationSample>> groups = new LinkedHashMap<>();
        for (AnnotationSample sample : samples) {
            groups.computeIfAbsent(sample.sourceKey, key -> new ArrayList<>()).add(sample);
        }
        Map<String, String> result = new HashMap<>();
        if (groups.size() > 1) {
            List<String> keys = new ArrayList<>(groups.keySet());
            keys.sort(Comparator.comparingInt(String::hashCode).thenComparing(value -> value));
            Set<String> validation = new HashSet<>();
            for (String key : keys) {
                if (Math.floorMod(key.hashCode(), 5) == 0) validation.add(key);
            }
            if (validation.isEmpty()) validation.add(keys.get(0));
            if (validation.size() == keys.size()) validation.remove(keys.get(keys.size() - 1));
            for (Map.Entry<String, List<AnnotationSample>> group : groups.entrySet()) {
                String split = validation.contains(group.getKey()) ? "val" : "train";
                for (AnnotationSample sample : group.getValue()) result.put(sample.id, split);
            }
            return result;
        }

        List<AnnotationSample> ordered = new ArrayList<>(samples);
        ordered.sort(Comparator.comparingLong(sample -> sample.timestampUs >= 0
                ? sample.timestampUs : sample.createdAt));
        int validationCount = Math.max(1, Math.round(ordered.size() * .20f));
        validationCount = Math.min(ordered.size() - 1, validationCount);
        int validationStart = ordered.size() - validationCount;
        for (int i = 0; i < ordered.size(); i++) {
            result.put(ordered.get(i).id, i >= validationStart ? "val" : "train");
        }
        return result;
    }

    private void write(List<AnnotationSample> samples) {
        try {
            JSONArray array = new JSONArray();
            for (AnnotationSample sample : samples) array.put(sample.toJson());
            File temp = new File(directory, INDEX + ".tmp");
            try (FileOutputStream output = new FileOutputStream(temp)) {
                output.write(array.toString().getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            }
            File target = new File(directory, INDEX);
            if (target.exists()) target.delete();
            if (!temp.renameTo(target)) throw new IllegalStateException("No se pudo actualizar el dataset");
        } catch (Exception error) {
            throw new IllegalStateException("No se pudo actualizar el dataset", error);
        }
    }

    private static void addFile(ZipOutputStream zip, String name, File file) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        try (FileInputStream input = new FileInputStream(file)) { copy(input, zip); }
        zip.closeEntry();
    }

    private static void addText(ZipOutputStream zip, String name, String text) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String readText(File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            copy(input, output);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static void copy(InputStream input, OutputStream output) throws Exception {
        byte[] buffer = new byte[64 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
    }

    private static float iou(AnnotationBox a, AnnotationBox b) {
        float left = Math.max(a.left, b.left), top = Math.max(a.top, b.top);
        float right = Math.min(a.right, b.right), bottom = Math.min(a.bottom, b.bottom);
        float intersection = Math.max(0f, right - left) * Math.max(0f, bottom - top);
        float areaA = (a.right - a.left) * (a.bottom - a.top);
        float areaB = (b.right - b.left) * (b.bottom - b.top);
        return intersection / Math.max(.000001f, areaA + areaB - intersection);
    }

    private static void rejectDuplicates(List<AnnotationBox> boxes) {
        for (int i = 0; i < boxes.size(); i++) {
            AnnotationBox first = boxes.get(i);
            if (AnnotationBox.REJECTED.equals(first.state)) continue;
            for (int j = i + 1; j < boxes.size(); j++) {
                AnnotationBox second = boxes.get(j);
                if (!first.label.equals(second.label) || AnnotationBox.REJECTED.equals(second.state)) continue;
                if (iou(first, second) > .90f) {
                    if (first.confidence >= second.confidence) second.state = AnnotationBox.REJECTED;
                    else first.state = AnnotationBox.REJECTED;
                }
            }
        }
    }

    static final class DatasetStats {
        final int samples, boxes, ready, review, rejected, classes;
        DatasetStats(int samples, int boxes, int ready, int review, int rejected, int classes) {
            this.samples = samples; this.boxes = boxes; this.ready = ready;
            this.review = review; this.rejected = rejected; this.classes = classes;
        }
    }

    static final class CurationResult {
        final int accepted, review, rejected;
        CurationResult(int accepted, int review, int rejected) {
            this.accepted = accepted; this.review = review; this.rejected = rejected;
        }
    }

    static final class ExportResult {
        final int train, val, classes;
        ExportResult(int train, int val, int classes) {
            this.train = train; this.val = val; this.classes = classes;
        }
    }

    static final class PurgeResult {
        final int boxes, samples;
        PurgeResult(int boxes, int samples) {
            this.boxes = boxes; this.samples = samples;
        }
    }

    interface Progress { void onProgress(int completed, int total); }

    static final class RescoreResult {
        final int accepted, review, rejected;
        RescoreResult(int accepted, int review, int rejected) {
            this.accepted = accepted; this.review = review; this.rejected = rejected;
        }
    }
}
