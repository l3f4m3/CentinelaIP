package com.fm.centinelaip;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Descubrimiento local, sin nube, para HCAM y cámaras ONVIF/RTSP genéricas. */
final class HcamDiscovery {
    private static final int[] RTSP_PORTS = {554, 8554};
    private static final String[] RTSP_PATHS = {
            "/11", "/12", "/live0.264", "/live1.264", "/ucast/11",
            "/live/ch0", "/live/ch1", "/stream1", "/stream2",
            "/live/ch00_0", "/live/ch00_1", "/onvif1", "/cam1/mpeg4", "/live.sdp"
    };
    private static final Pattern XADDR_PATTERN = Pattern.compile(
            "https?://([0-9]{1,3}(?:\\.[0-9]{1,3}){3})(?::([0-9]+))?", Pattern.CASE_INSENSITIVE);

    interface Listener {
        void onProgress(String message);
        void onFinished(Result result);
    }

    static final class Result {
        final boolean success;
        final String rtspUrl;
        final String ipAddress;
        final String connectedSsid;
        final boolean credentialsRejected;
        final String report;

        Result(boolean success, String rtspUrl, String ipAddress, String connectedSsid,
               boolean credentialsRejected, String report) {
            this.success = success;
            this.rtspUrl = rtspUrl == null ? "" : rtspUrl;
            this.ipAddress = ipAddress == null ? "" : ipAddress;
            this.connectedSsid = connectedSsid == null ? "" : connectedSsid;
            this.credentialsRejected = credentialsRejected;
            this.report = report == null ? "" : report;
        }
    }

    private HcamDiscovery() { }

    static void discover(Context context, String expectedUuid, String username,
                         String password, Listener listener) {
        Context app = context.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        ExecutorService worker = Executors.newSingleThreadExecutor();
        worker.execute(() -> {
            Result result;
            try {
                result = discoverBlocking(app, expectedUuid, username, password,
                        message -> main.post(() -> listener.onProgress(message)));
            } catch (Exception error) {
                result = new Result(false, "", "", currentSsid(app), false,
                        "Error durante el descubrimiento: " + error.getClass().getSimpleName()
                                + " · " + safeMessage(error));
            }
            Result completed = result;
            main.post(() -> listener.onFinished(completed));
            worker.shutdown();
        });
    }

    private interface Progress { void update(String value); }

    private static Result discoverBlocking(Context context, String expectedUuid,
                                            String username, String password,
                                            Progress progress) throws Exception {
        ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
        Network wifi = findWifiNetwork(connectivity);
        String ssid = currentSsid(context);
        String normalizedUuid = CameraConfig.normalizeUuid(expectedUuid);
        if (wifi == null) {
            return new Result(false, "", "", ssid, false,
                    "No hay una conexión Wi‑Fi activa. Conecta el teléfono al punto de acceso "
                            + "de la cámara y elige mantener la conexión sin Internet.");
        }

        LinkProperties links = connectivity.getLinkProperties(wifi);
        if (links == null) {
            return new Result(false, "", "", ssid, false,
                    "Android no entregó los parámetros de la red Wi‑Fi activa.");
        }

        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        String localIp = "";
        String gateway = "";
        for (LinkAddress address : links.getLinkAddresses()) {
            if (address.getAddress() instanceof Inet4Address) {
                localIp = address.getAddress().getHostAddress();
                break;
            }
        }
        for (RouteInfo route : links.getRoutes()) {
            InetAddress value = route.getGateway();
            if (value instanceof Inet4Address) {
                gateway = value.getHostAddress();
                candidates.add(gateway);
            }
        }
        if (!localIp.isBlank()) candidates.add(subnetHost(localIp, 1));

        StringBuilder report = new StringBuilder();
        report.append("UUID esperado: ").append(normalizedUuid.isBlank() ? "(no indicado)" : normalizedUuid).append('\n');
        report.append("Wi‑Fi: ").append(ssid.isBlank() ? "(SSID no visible; concede permiso de red cercana)" : ssid).append('\n');
        report.append("IP del teléfono: ").append(localIp.isBlank() ? "desconocida" : localIp).append('\n');
        report.append("Puerta de enlace: ").append(gateway.isBlank() ? "no anunciada" : gateway).append('\n');

        progress.update("Buscando anuncios ONVIF en la red Wi‑Fi…");
        candidates.addAll(discoverOnvif(context, wifi));
        report.append("Candidatos iniciales: ").append(candidates).append('\n');

        String user = username == null || username.isBlank() ? "admin" : username;
        String pass = password == null ? "" : password;
        ProbeResult locked = null;

        progress.update("Probando el flujo HCAM y rutas RTSP conocidas…");
        for (String host : new ArrayList<>(candidates)) {
            ProbeResult probe = probeHost(wifi, host, user, pass);
            if (probe.success) {
                report.append("RTSP confirmado: ").append(redact(probe.url)).append('\n');
                return new Result(true, probe.url, host, ssid, false, report.toString());
            }
            if (probe.credentialsRejected && locked == null) locked = probe;
        }

        if (!localIp.isBlank()) {
            progress.update("Escaneando el segmento local para localizar cámaras RTSP…");
            Set<String> rtspHosts = scanRtspHosts(wifi, localIp);
            candidates.addAll(rtspHosts);
            report.append("Equipos con puerto RTSP: ").append(rtspHosts).append('\n');
            for (String host : rtspHosts) {
                ProbeResult probe = probeHost(wifi, host, user, pass);
                if (probe.success) {
                    report.append("RTSP confirmado: ").append(redact(probe.url)).append('\n');
                    return new Result(true, probe.url, host, ssid, false, report.toString());
                }
                if (probe.credentialsRejected && locked == null) locked = probe;
            }
        }

        if (locked != null) {
            report.append("Se encontró un servidor RTSP, pero rechazó usuario/contraseña.\n");
            report.append("Ruta candidata: ").append(redact(locked.url)).append('\n');
            return new Result(false, locked.url, Uri.parse(locked.url).getHost(), ssid,
                    true, report.toString());
        }

        boolean directAp = (!normalizedUuid.isBlank()
                && normalizeSsid(ssid).contains(normalizedUuid))
                || normalizeSsid(ssid).contains("HCAM");
        if (directAp && !gateway.isBlank()) {
            report.append("La cámara respondió como punto de acceso en ").append(gateway)
                    .append(", pero no publicó un flujo RTSP/ONVIF estándar.\n")
                    .append("El video por UUID usa ThroughTek/Kalay y necesita el SDK y las claves de licencia del fabricante.");
            return new Result(false, "", gateway, ssid, false, report.toString());
        }
        report.append("No se confirmó un flujo RTSP en esta red. Si la cámara ya está en el router, "
                + "verifica que el teléfono esté en el mismo Wi‑Fi de 2.4 GHz.");
        return new Result(false, "", "", ssid, false, report.toString());
    }

    private static Network findWifiNetwork(ConnectivityManager connectivity) {
        for (Network network : connectivity.getAllNetworks()) {
            NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
            if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return network;
            }
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private static String currentSsid(Context context) {
        try {
            WifiManager manager = context.getSystemService(WifiManager.class);
            WifiInfo info = manager == null ? null : manager.getConnectionInfo();
            if (info == null) return "";
            String ssid = info.getSSID();
            if (ssid == null || "<unknown ssid>".equalsIgnoreCase(ssid)) return "";
            return ssid.replace("\"", "");
        } catch (SecurityException ignored) {
            return "";
        }
    }

    private static String normalizeSsid(String value) {
        return value == null ? "" : value.toUpperCase(Locale.US)
                .replace("-", "").replace("_", "").replace(" ", "");
    }

    private static String subnetHost(String localIp, int host) {
        int dot = localIp.lastIndexOf('.');
        return dot < 0 ? localIp : localIp.substring(0, dot + 1) + host;
    }

    private static Set<String> discoverOnvif(Context context, Network network) {
        LinkedHashSet<String> found = new LinkedHashSet<>();
        WifiManager manager = context.getSystemService(WifiManager.class);
        WifiManager.MulticastLock lock = null;
        DatagramSocket socket = null;
        try {
            if (manager != null) {
                lock = manager.createMulticastLock("centinela-onvif");
                lock.setReferenceCounted(false);
                lock.acquire();
            }
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(0));
            network.bindSocket(socket);
            socket.setSoTimeout(450);
            String messageId = "uuid:" + UUID.randomUUID();
            String probe = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<e:Envelope xmlns:e=\"http://www.w3.org/2003/05/soap-envelope\" "
                    + "xmlns:w=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\" "
                    + "xmlns:d=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\" "
                    + "xmlns:dn=\"http://www.onvif.org/ver10/network/wsdl\">"
                    + "<e:Header><w:MessageID>" + messageId + "</w:MessageID>"
                    + "<w:To e:mustUnderstand=\"true\">urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To>"
                    + "<w:Action e:mustUnderstand=\"true\">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action>"
                    + "</e:Header><e:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types>"
                    + "</d:Probe></e:Body></e:Envelope>";
            byte[] payload = probe.getBytes(StandardCharsets.UTF_8);
            DatagramPacket request = new DatagramPacket(payload, payload.length,
                    InetAddress.getByName("239.255.255.250"), 3702);
            socket.send(request);
            long deadline = System.currentTimeMillis() + 1800L;
            byte[] buffer = new byte[16_384];
            while (System.currentTimeMillis() < deadline) {
                try {
                    DatagramPacket response = new DatagramPacket(buffer, buffer.length);
                    socket.receive(response);
                    String xml = new String(response.getData(), response.getOffset(),
                            response.getLength(), StandardCharsets.UTF_8);
                    Matcher matcher = XADDR_PATTERN.matcher(xml);
                    while (matcher.find()) found.add(matcher.group(1));
                    if (response.getAddress() instanceof Inet4Address) {
                        found.add(response.getAddress().getHostAddress());
                    }
                } catch (java.net.SocketTimeoutException ignored) { }
            }
        } catch (Exception ignored) {
        } finally {
            if (socket != null) socket.close();
            if (lock != null && lock.isHeld()) lock.release();
        }
        return found;
    }

    private static Set<String> scanRtspHosts(Network network, String localIp) {
        LinkedHashSet<String> found = new LinkedHashSet<>();
        int dot = localIp.lastIndexOf('.');
        if (dot < 0) return found;
        String prefix = localIp.substring(0, dot + 1);
        ExecutorService pool = Executors.newFixedThreadPool(36);
        CompletionService<String> completion = new ExecutorCompletionService<>(pool);
        int submitted = 0;
        for (int value = 1; value < 255; value++) {
            String host = prefix + value;
            if (host.equals(localIp)) continue;
            submitted++;
            completion.submit((Callable<String>) () -> {
                for (int port : RTSP_PORTS) {
                    if (isPortOpen(network, host, port, 170)) return host;
                }
                return "";
            });
        }
        try {
            for (int index = 0; index < submitted; index++) {
                Future<String> next = completion.take();
                String host = next.get();
                if (!host.isBlank()) found.add(host);
            }
        } catch (Exception ignored) {
        } finally {
            pool.shutdownNow();
        }
        return found;
    }

    private static boolean isPortOpen(Network network, String host, int port, int timeoutMs) {
        try (Socket socket = network.getSocketFactory().createSocket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static final class ProbeResult {
        final boolean success;
        final boolean credentialsRejected;
        final String url;

        ProbeResult(boolean success, boolean credentialsRejected, String url) {
            this.success = success;
            this.credentialsRejected = credentialsRejected;
            this.url = url;
        }
    }

    private static ProbeResult probeHost(Network network, String host, String username, String password) {
        ProbeResult locked = null;
        for (int port : RTSP_PORTS) {
            if (!isPortOpen(network, host, port, 260)) continue;
            for (String path : RTSP_PATHS) {
                String url = "rtsp://" + host + ":" + port + path;
                RtspResponse first = requestRtsp(network, host, port, url, "");
                if (first.status == 200) return new ProbeResult(true, false, url);
                if (first.status == 401) {
                    String authorization = authorization(first.challenge, username, password, url);
                    if (!authorization.isBlank()) {
                        RtspResponse authenticated = requestRtsp(network, host, port, url, authorization);
                        if (authenticated.status == 200) return new ProbeResult(true, false, url);
                    }
                    if (locked == null) locked = new ProbeResult(false, true, url);
                }
            }
        }
        return locked == null ? new ProbeResult(false, false, "") : locked;
    }

    private static final class RtspResponse {
        final int status;
        final String challenge;
        RtspResponse(int status, String challenge) {
            this.status = status;
            this.challenge = challenge == null ? "" : challenge;
        }
    }

    private static RtspResponse requestRtsp(Network network, String host, int port,
                                            String url, String authorization) {
        try (Socket socket = network.getSocketFactory().createSocket()) {
            socket.connect(new InetSocketAddress(host, port), 650);
            socket.setSoTimeout(900);
            StringBuilder request = new StringBuilder()
                    .append("DESCRIBE ").append(url).append(" RTSP/1.0\r\n")
                    .append("CSeq: 1\r\n")
                    .append("Accept: application/sdp\r\n")
                    .append("User-Agent: CentinelaIP/0.4\r\n");
            if (!authorization.isBlank()) request.append("Authorization: ").append(authorization).append("\r\n");
            request.append("\r\n");
            BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream());
            output.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
            output.flush();

            BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
            byte[] data = new byte[16_384];
            int length = 0;
            while (length < data.length) {
                int value = input.read();
                if (value < 0) break;
                data[length++] = (byte) value;
                if (length >= 4 && data[length - 4] == '\r' && data[length - 3] == '\n'
                        && data[length - 2] == '\r' && data[length - 1] == '\n') break;
            }
            String headers = new String(data, 0, length, StandardCharsets.ISO_8859_1);
            int status = 0;
            Matcher statusMatcher = Pattern.compile("RTSP/\\d\\.\\d\\s+(\\d{3})").matcher(headers);
            if (statusMatcher.find()) status = Integer.parseInt(statusMatcher.group(1));
            String challenge = "";
            Matcher challengeMatcher = Pattern.compile("(?im)^WWW-Authenticate:\\s*(.+)$").matcher(headers);
            if (challengeMatcher.find()) challenge = challengeMatcher.group(1).trim();
            return new RtspResponse(status, challenge);
        } catch (Exception ignored) {
            return new RtspResponse(0, "");
        }
    }

    private static String authorization(String challenge, String username, String password, String url) {
        if (challenge == null) return "";
        if (challenge.regionMatches(true, 0, "Basic", 0, 5)) {
            String token = Base64.encodeToString((username + ":" + password)
                    .getBytes(StandardCharsets.ISO_8859_1), Base64.NO_WRAP);
            return "Basic " + token;
        }
        if (!challenge.regionMatches(true, 0, "Digest", 0, 6)) return "";
        try {
            String realm = parameter(challenge, "realm");
            String nonce = parameter(challenge, "nonce");
            String qopValue = parameter(challenge, "qop");
            String qop = qopValue.toLowerCase(Locale.US).contains("auth") ? "auth" : "";
            String opaque = parameter(challenge, "opaque");
            // RTSP usa normalmente la URI absoluta de la línea DESCRIBE en el digest.
            String uri = url;
            String ha1 = md5(username + ":" + realm + ":" + password);
            String ha2 = md5("DESCRIBE:" + uri);
            String response;
            String cnonce = Long.toHexString(System.nanoTime());
            String nc = "00000001";
            if (qop.isBlank()) response = md5(ha1 + ":" + nonce + ":" + ha2);
            else response = md5(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":" + qop + ":" + ha2);
            StringBuilder value = new StringBuilder("Digest username=\"").append(username)
                    .append("\", realm=\"").append(realm)
                    .append("\", nonce=\"").append(nonce)
                    .append("\", uri=\"").append(uri)
                    .append("\", response=\"").append(response).append("\"");
            if (!opaque.isBlank()) value.append(", opaque=\"").append(opaque).append("\"");
            if (!qop.isBlank()) value.append(", qop=").append(qop)
                    .append(", nc=").append(nc).append(", cnonce=\"").append(cnonce).append("\"");
            return value.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String parameter(String header, String key) {
        Matcher matcher = Pattern.compile("(?i)(?:^|[,\\s])" + Pattern.quote(key)
                + "\\s*=\\s*(?:\"([^\"]*)\"|([^,\\s]+))").matcher(header);
        if (!matcher.find()) return "";
        return matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
    }

    private static String md5(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("MD5")
                .digest(value.getBytes(StandardCharsets.ISO_8859_1));
        StringBuilder hex = new StringBuilder();
        for (byte item : digest) hex.append(String.format(Locale.US, "%02x", item & 0xff));
        return hex.toString();
    }

    private static String redact(String url) {
        Uri uri = Uri.parse(url);
        if (uri.getUserInfo() == null) return url;
        return url.replace(uri.getUserInfo() + "@", "***:***@");
    }

    private static String safeMessage(Exception error) {
        return error.getMessage() == null ? "sin detalle" : error.getMessage();
    }
}
