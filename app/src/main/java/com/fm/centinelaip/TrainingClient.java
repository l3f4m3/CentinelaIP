package com.fm.centinelaip;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

final class TrainingClient {
    private final String endpoint;
    private final String token;

    TrainingClient(String endpoint, String token) {
        if (endpoint == null || !(endpoint.startsWith("http://") || endpoint.startsWith("https://"))) {
            throw new IllegalArgumentException("Usa una URL http:// o https:// válida");
        }
        this.endpoint = endpoint.replaceAll("/+$", "");
        this.token = token == null ? "" : token;
    }

    String health() throws Exception {
        JSONObject value = requestJson("GET", "/health");
        return value.optString("service", "Servidor disponible");
    }

    String start(File dataset, int epochs) throws Exception {
        String boundary = "Centinela-" + UUID.randomUUID();
        HttpURLConnection connection = open("POST", "/train");
        connection.setDoOutput(true);
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(120_000);
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        try (OutputStream output = connection.getOutputStream()) {
            field(output, boundary, "epochs", Integer.toString(epochs));
            output.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            output.write("Content-Disposition: form-data; name=\"dataset\"; filename=\"dataset.zip\"\r\n"
                    .getBytes(StandardCharsets.UTF_8));
            output.write("Content-Type: application/zip\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            try (FileInputStream input = new FileInputStream(dataset)) { copy(input, output); }
            output.write("\r\n".getBytes(StandardCharsets.UTF_8));
            output.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        }
        JSONObject response = parseResponse(connection);
        String id = response.optString("job_id");
        if (id.isEmpty()) throw new IllegalStateException("El servidor no devolvió job_id");
        return id;
    }

    JobStatus status(String id) throws Exception {
        JSONObject value = requestJson("GET", "/jobs/" + id);
        return new JobStatus(
                value.optString("status", "unknown"),
                (float) value.optDouble("progress", 0),
                value.optString("message", ""));
    }

    void download(String id, File destination) throws Exception {
        HttpURLConnection connection = open("GET", "/jobs/" + id + "/model");
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(180_000);
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) throw httpError(connection, code);
        File temporary = new File(destination.getParentFile(), destination.getName() + ".part");
        try (InputStream input = connection.getInputStream();
             FileOutputStream output = new FileOutputStream(temporary)) {
            copy(input, output);
        } finally {
            connection.disconnect();
        }
        if (destination.exists()) destination.delete();
        if (!temporary.renameTo(destination)) throw new IllegalStateException("No se pudo guardar el modelo");
    }

    private JSONObject requestJson(String method, String path) throws Exception {
        HttpURLConnection connection = open(method, path);
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        return parseResponse(connection);
    }

    private HttpURLConnection open(String method, String path) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint + path).openConnection();
        connection.setRequestMethod(method);
        connection.setRequestProperty("Accept", "application/json");
        if (!token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);
        return connection;
    }

    private static JSONObject parseResponse(HttpURLConnection connection) throws Exception {
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) throw httpError(connection, code);
        try (InputStream input = connection.getInputStream()) {
            return new JSONObject(read(input));
        } finally {
            connection.disconnect();
        }
    }

    private static Exception httpError(HttpURLConnection connection, int code) {
        String message = "HTTP " + code;
        try {
            InputStream error = connection.getErrorStream();
            if (error != null) message += " · " + read(error);
        } catch (Exception ignored) { }
        connection.disconnect();
        return new IllegalStateException(message);
    }

    private static void field(OutputStream output, String boundary, String name, String value) throws Exception {
        output.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        output.write(value.getBytes(StandardCharsets.UTF_8));
        output.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static String read(InputStream input) throws Exception {
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            copy(source, output);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static void copy(InputStream input, OutputStream output) throws Exception {
        byte[] buffer = new byte[64 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
    }

    static final class JobStatus {
        final String status;
        final float progress;
        final String message;
        JobStatus(String status, float progress, String message) {
            this.status = status; this.progress = progress; this.message = message;
        }
        boolean ready() { return "completed".equals(status); }
        boolean terminal() { return ready() || "failed".equals(status); }
    }
}
