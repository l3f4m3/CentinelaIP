package com.fm.centinelaip;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;

/** Inferencia YOLO26n ONNX ejecutada íntegramente en el dispositivo. */
final class YoloDetector implements AutoCloseable {
    static final int INPUT_SIZE = 640;
    private static final int MAX_DETECTIONS = 100;

    private static final int[] PALETTE = {
            Color.rgb(85, 230, 193), Color.rgb(65, 164, 255), Color.rgb(255, 85, 116),
            Color.rgb(255, 200, 87), Color.rgb(179, 136, 255), Color.rgb(90, 200, 250)
    };

    private final OrtEnvironment environment;
    private final OrtSession session;
    private final String inputName;
    private final List<String> labels;
    private final RoadObjectTracker tracker = new RoadObjectTracker();
    private final int[] pixels = new int[INPUT_SIZE * INPUT_SIZE];
    private final FloatBuffer inputBuffer = ByteBuffer
            .allocateDirect(3 * INPUT_SIZE * INPUT_SIZE * Float.BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer();

    YoloDetector(Context context) throws Exception {
        this(context, new ModelStore(context).active());
    }

    YoloDetector(Context context, ModelSpec spec) throws Exception {
        this(readModel(context, spec), spec.labels);
    }

    YoloDetector(Context context, File model, List<String> labels) throws Exception {
        this(readStream(new FileInputStream(model)), labels);
    }

    private YoloDetector(byte[] model, List<String> labels) throws Exception {
        if (labels == null || labels.isEmpty()) {
            throw new IllegalArgumentException("El modelo no tiene etiquetas");
        }
        this.labels = new ArrayList<>(labels);
        environment = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        options.setIntraOpNumThreads(Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() - 1)));
        options.setInterOpNumThreads(1);
        session = environment.createSession(model, options);
        inputName = session.getInputNames().iterator().next();

        TensorInfo inputInfo = (TensorInfo) session.getInputInfo().get(inputName).getInfo();
        long[] shape = inputInfo.getShape();
        if (shape.length != 4 || shape[2] != INPUT_SIZE || shape[3] != INPUT_SIZE) {
            throw new IllegalArgumentException("El modelo YOLO26n no tiene entrada 1x3x640x640");
        }
        if (session.getOutputInfo().isEmpty()) {
            session.close();
            throw new IllegalArgumentException("El modelo ONNX no tiene salida");
        }
        Object outputInfo = session.getOutputInfo().values().iterator().next().getInfo();
        if (!(outputInfo instanceof TensorInfo)) {
            session.close();
            throw new IllegalArgumentException("La salida ONNX no es un tensor");
        }
        long[] outputShape = ((TensorInfo) outputInfo).getShape();
        if (outputShape.length != 3 || (outputShape[0] > 0 && outputShape[0] != 1)
                || (outputShape[2] > 0 && outputShape[2] != 6)) {
            session.close();
            throw new IllegalArgumentException(
                    "La salida debe ser YOLO26 end-to-end con forma 1xNx6");
        }
    }

    List<Detection> detect(Bitmap source, float threshold) throws OrtException {
        float scale = Math.min((float) INPUT_SIZE / source.getWidth(), (float) INPUT_SIZE / source.getHeight());
        int scaledWidth = Math.max(1, Math.round(source.getWidth() * scale));
        int scaledHeight = Math.max(1, Math.round(source.getHeight() * scale));
        float padX = (INPUT_SIZE - scaledWidth) / 2f;
        float padY = (INPUT_SIZE - scaledHeight) / 2f;

        Bitmap letterboxed = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(letterboxed);
        canvas.drawColor(Color.rgb(114, 114, 114));
        Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(source, null, new RectF(padX, padY, padX + scaledWidth, padY + scaledHeight), bitmapPaint);
        letterboxed.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE);
        letterboxed.recycle();

        inputBuffer.clear();
        for (int pixel : pixels) inputBuffer.put(((pixel >> 16) & 0xFF) / 255f);
        for (int pixel : pixels) inputBuffer.put(((pixel >> 8) & 0xFF) / 255f);
        for (int pixel : pixels) inputBuffer.put((pixel & 0xFF) / 255f);
        inputBuffer.rewind();

        try (OnnxTensor tensor = OnnxTensor.createTensor(
                environment, inputBuffer, new long[]{1, 3, INPUT_SIZE, INPUT_SIZE});
             OrtSession.Result result = session.run(Collections.singletonMap(inputName, tensor))) {
            OnnxTensor output = (OnnxTensor) result.get(0);
            long[] shape = ((TensorInfo) output.getInfo()).getShape();
            if (shape.length != 3 || shape[0] != 1 || shape[2] < 6) {
                throw new IllegalStateException("Salida YOLO26 no compatible");
            }

            FloatBuffer outputBuffer = output.getFloatBuffer();
            outputBuffer.rewind();
            float[] values = new float[outputBuffer.remaining()];
            outputBuffer.get(values);
            int rows = (int) shape[1];
            int columns = (int) shape[2];
            List<Detection> candidates = new ArrayList<>();

            for (int row = 0; row < rows; row++) {
                int offset = row * columns;
                float confidence = values[offset + 4];
                if (confidence < threshold) continue;
                int classId = Math.round(values[offset + 5]);
                if (classId < 0 || classId >= labels.size()) continue;

                float left = clamp((values[offset] - padX) / scale, 0f, source.getWidth());
                float top = clamp((values[offset + 1] - padY) / scale, 0f, source.getHeight());
                float right = clamp((values[offset + 2] - padX) / scale, 0f, source.getWidth());
                float bottom = clamp((values[offset + 3] - padY) / scale, 0f, source.getHeight());
                if (right - left < 2f || bottom - top < 2f) continue;

                candidates.add(new Detection(left, top, right, bottom, confidence, classId,
                        labels.get(classId), PALETTE[classId % PALETTE.length]));
            }
            List<Detection> finalDetections = highestConfidence(candidates);
            return tracker.update(finalDetections, source.getWidth(), source.getHeight(),
                    System.nanoTime()).detections;
        }
    }

    void resetTracking() {
        tracker.reset();
    }

    /** YOLO26 end-to-end ya produce detecciones finales; solo ordenamos y limitamos el dibujo. */
    private List<Detection> highestConfidence(List<Detection> candidates) {
        candidates.sort(Comparator.comparingDouble((Detection d) -> d.confidence).reversed());
        if (candidates.size() <= MAX_DETECTIONS) return candidates;
        return new ArrayList<>(candidates.subList(0, MAX_DETECTIONS));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static byte[] readModel(Context context, ModelSpec spec) throws Exception {
        try (InputStream input = new ModelStore(context).openModel(spec)) {
            return readStream(input);
        }
    }

    private static byte[] readStream(InputStream input) throws Exception {
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024 * 1024];
            int count;
            while ((count = source.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    @Override
    public void close() throws Exception {
        session.close();
    }
}
