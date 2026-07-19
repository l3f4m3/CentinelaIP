package com.fm.centinelaip;

import android.content.Context;

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
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Paquetes portables: model.onnx + labels.txt + manifest.json. */
final class ModelStore {
    static final String BUNDLED_ID = "bundled-yolo26n-coco";
    private static final String INDEX_FILE = "models.json";
    private static final String PREFS = "centinela_models";
    private static final String ACTIVE_KEY = "active_model";
    private static final long MAX_MODEL_BYTES = 300L * 1024L * 1024L;

    private final Context context;
    private final File directory;

    ModelStore(Context context) {
        this.context = context.getApplicationContext();
        directory = new File(this.context.getFilesDir(), "models");
        if (!directory.exists()) directory.mkdirs();
    }

    synchronized List<ModelSpec> list() {
        List<ModelSpec> models = new ArrayList<>();
        models.add(bundled());
        File index = new File(directory, INDEX_FILE);
        if (!index.exists()) return models;
        try {
            JSONArray array = new JSONArray(readText(index));
            for (int i = 0; i < array.length(); i++) {
                ModelSpec spec = ModelSpec.fromJson(array.getJSONObject(i));
                if (!spec.id.isEmpty() && !spec.labels.isEmpty()
                        && modelFile(spec).isFile()) models.add(spec);
            }
        } catch (Exception ignored) { }
        return models;
    }

    synchronized ModelSpec active() {
        String id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(ACTIVE_KEY, BUNDLED_ID);
        for (ModelSpec spec : list()) if (spec.id.equals(id)) return spec;
        setActive(BUNDLED_ID);
        return bundled();
    }

    synchronized void setActive(String id) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(ACTIVE_KEY, id).apply();
    }

    synchronized ModelSpec importPackage(InputStream source) throws Exception {
        String id = UUID.randomUUID().toString();
        File temporary = new File(directory, ".import-" + id);
        if (!temporary.mkdirs()) throw new IllegalStateException("No fue posible preparar la importación");
        File model = new File(temporary, "model.onnx");
        File labelsFile = new File(temporary, "labels.txt");
        File manifestFile = new File(temporary, "manifest.json");

        try (ZipInputStream zip = new ZipInputStream(source)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String leaf = new File(entry.getName()).getName().toLowerCase(Locale.ROOT);
                File target = null;
                if ("model.onnx".equals(leaf)) target = model;
                if ("labels.txt".equals(leaf)) target = labelsFile;
                if ("manifest.json".equals(leaf)) target = manifestFile;
                if (target != null) copyLimited(zip, target, MAX_MODEL_BYTES);
                zip.closeEntry();
            }
        }

        if (!model.isFile() || !labelsFile.isFile()) {
            deleteTree(temporary);
            throw new IllegalArgumentException("El ZIP debe contener model.onnx y labels.txt");
        }
        List<String> labels = readLabels(new FileInputStream(labelsFile));
        if (labels.isEmpty()) {
            deleteTree(temporary);
            throw new IllegalArgumentException("labels.txt está vacío");
        }
        String name = "Modelo importado";
        if (manifestFile.isFile()) {
            try {
                name = new JSONObject(readText(manifestFile)).optString("name", name).trim();
            } catch (Exception ignored) { }
        }
        if (name.isEmpty()) name = "Modelo importado";

        try (YoloDetector ignored = new YoloDetector(context, model, labels)) {
            // La creación de la sesión valida entrada, salida y operadores ONNX.
        } catch (Exception error) {
            deleteTree(temporary);
            throw new IllegalArgumentException("Modelo YOLO26/ONNX no compatible", error);
        }

        File destination = new File(directory, id);
        if (!temporary.renameTo(destination)) {
            deleteTree(temporary);
            throw new IllegalStateException("No fue posible finalizar la importación");
        }
        ModelSpec spec = new ModelSpec(id, name, "model.onnx", labels, false,
                System.currentTimeMillis());
        List<ModelSpec> models = list();
        models.add(spec);
        writeIndex(models);
        setActive(id);
        return spec;
    }

    synchronized void exportPackage(ModelSpec spec, OutputStream destination) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(destination)) {
            zip.putNextEntry(new ZipEntry("model.onnx"));
            try (InputStream input = openModel(spec)) { copy(input, zip); }
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("labels.txt"));
            for (String label : spec.labels) {
                zip.write(label.getBytes(StandardCharsets.UTF_8));
                zip.write('\n');
            }
            zip.closeEntry();

            JSONObject manifest = new JSONObject();
            manifest.put("name", spec.name);
            manifest.put("format", "centinela-yolo-onnx-v1");
            manifest.put("input", "1x3x640x640");
            manifest.put("output", "1x300x6");
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(manifest.toString(2).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    synchronized void delete(ModelSpec spec) {
        if (spec.bundled) return;
        deleteTree(new File(directory, spec.id));
        if (active().id.equals(spec.id)) setActive(BUNDLED_ID);
        writeIndex(list());
    }

    InputStream openModel(ModelSpec spec) throws Exception {
        return spec.bundled
                ? context.getAssets().open("yolo26n.onnx")
                : new FileInputStream(modelFile(spec));
    }

    private ModelSpec bundled() {
        List<String> labels;
        try {
            labels = readLabels(context.getAssets().open("coco_es.txt"));
        } catch (Exception error) {
            labels = new ArrayList<>();
        }
        return new ModelSpec(BUNDLED_ID, "YOLO26n · COCO", "yolo26n.onnx",
                labels, true, 0L);
    }

    private File modelFile(ModelSpec spec) {
        return new File(new File(directory, spec.id), spec.fileName);
    }

    private void writeIndex(List<ModelSpec> models) {
        try {
            JSONArray array = new JSONArray();
            for (ModelSpec spec : models) if (!spec.bundled) array.put(spec.toJson());
            File temp = new File(directory, INDEX_FILE + ".tmp");
            try (FileOutputStream output = new FileOutputStream(temp)) {
                output.write(array.toString().getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            }
            File target = new File(directory, INDEX_FILE);
            if (target.exists()) target.delete();
            if (!temp.renameTo(target)) throw new IllegalStateException("No fue posible guardar modelos");
        } catch (Exception error) {
            throw new IllegalStateException("No fue posible guardar modelos", error);
        }
    }

    static List<String> readLabels(InputStream input) throws Exception {
        List<String> labels = new ArrayList<>();
        String text;
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            copy(source, output);
            text = output.toString(StandardCharsets.UTF_8.name());
        }
        for (String line : text.split("\\R")) {
            String value = line.trim();
            if (!value.isEmpty() && !labels.contains(value)) labels.add(value);
        }
        return labels;
    }

    private static String readText(File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            copy(input, output);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static void copyLimited(InputStream input, File target, long maximum) throws Exception {
        long total = 0L;
        try (FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > maximum) throw new IllegalArgumentException("El paquete supera 300 MB");
                output.write(buffer, 0, count);
            }
        }
    }

    private static void copy(InputStream input, OutputStream output) throws Exception {
        byte[] buffer = new byte[64 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
    }

    private static void deleteTree(File target) {
        if (target == null || !target.exists()) return;
        File[] children = target.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        target.delete();
    }
}
