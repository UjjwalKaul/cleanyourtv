package io.github.cleanyourtv.airemote.server;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Base64;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.cleanyourtv.airemote.dispatcher.AppCatalog;
import io.github.cleanyourtv.airemote.dispatcher.CommandDispatcher;

public final class NativeServer {

    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int SOCKET_TIMEOUT_MS = 15000;
    private static final int MAX_CONCURRENT_CONNECTIONS = 32;

    public static final class ClientInfo {
        public final String id;
        public final String ip;
        public final String userAgent;
        public volatile String deviceName;
        public final Socket socket;

        public ClientInfo(String id, String ip, String userAgent, String deviceName, Socket socket) {
            this.id = id;
            this.ip = ip;
            this.userAgent = userAgent;
            this.deviceName = deviceName;
            this.socket = socket;
        }
    }

    public interface StatusListener {
        void onClientsChanged(List<ClientInfo> clients);
    }

    private final Context context;
    private final int port;
    private final int httpsPort;
    private final String localIp;
    private final SessionAuth auth;
    private final CommandDispatcher dispatcher;
    private volatile WeakReference<StatusListener> statusListenerRef;

    private volatile boolean running = false;
    private ServerSocket serverSocket;
    private ServerSocket sslServerSocket;
    private ExecutorService threadPool;
    private final Map<String, ClientInfo> activeClients = new ConcurrentHashMap<>();
    private final AtomicInteger activeConnections = new AtomicInteger(0);

    public NativeServer(Context context, int port, SessionAuth auth, StatusListener statusListener) {
        this(context, port, 8443, null, auth, statusListener);
    }

    public NativeServer(Context context, int port, int httpsPort, String localIp, SessionAuth auth, StatusListener statusListener) {
        this.context = context.getApplicationContext();
        this.port = port;
        this.httpsPort = httpsPort;
        this.localIp = localIp;
        this.auth = auth;
        this.dispatcher = new CommandDispatcher(context);
        this.statusListenerRef = new WeakReference<>(statusListener);
    }

    public void setStatusListener(StatusListener listener) {
        this.statusListenerRef = new WeakReference<>(listener);
        notifyClientsChanged();
    }

    public SessionAuth getAuth() {
        return auth;
    }

    public synchronized void start() throws Exception {
        if (running) return;
        serverSocket = new ServerSocket(port);

        if (httpsPort > 0) {
            try {
                javax.net.ssl.SSLContext sslContext = SslContextFactory.createEphemeralSslContext(localIp);
                sslServerSocket = sslContext.getServerSocketFactory().createServerSocket(httpsPort);
            } catch (Exception e) {
                android.util.Log.e("NativeServer", "Failed to start HTTPS server on port " + httpsPort, e);
            }
        }

        // Cached thread pool prevents deadlocks and connection starvation on web client refresh
        threadPool = Executors.newCachedThreadPool();
        running = true;

        threadPool.execute(new Runnable() {
            @Override
            public void run() {
                while (running) {
                    try {
                        Socket socket = serverSocket.accept();
                        socket.setTcpNoDelay(true);
                        socket.setSoTimeout(SOCKET_TIMEOUT_MS);
                        handleConnection(socket);
                    } catch (Exception e) {
                        if (!running) break;
                    }
                }
            }
        });

        if (sslServerSocket != null) {
            threadPool.execute(new Runnable() {
                @Override
                public void run() {
                    while (running) {
                        try {
                            Socket socket = sslServerSocket.accept();
                            socket.setTcpNoDelay(true);
                            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
                            handleConnection(socket);
                        } catch (Exception e) {
                            if (!running) break;
                        }
                    }
                }
            });
        }
    }

    public synchronized void stop() {
        running = false;
        disconnectAllClients();
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception ignored) {}
        try {
            if (sslServerSocket != null) sslServerSocket.close();
        } catch (Exception ignored) {}
        if (threadPool != null) threadPool.shutdownNow();
        dispatcher.shutdown();
    }

    public void disconnectAllClients() {
        for (ClientInfo client : activeClients.values()) {
            try {
                client.socket.close();
            } catch (Exception ignored) {}
        }
        activeClients.clear();
        notifyClientsChanged();
    }

    public void severClient(String clientId) {
        ClientInfo client = activeClients.remove(clientId);
        if (client != null) {
            try {
                WsFrame.sendFrame(client.socket.getOutputStream(), WsFrame.OPCODE_CLOSE, new byte[0]);
            } catch (Exception ignored) {}
            try {
                client.socket.close();
            } catch (Exception ignored) {}
        }
        notifyClientsChanged();
    }

    public void broadcast(final String message) {
        if (threadPool != null && running) {
            threadPool.execute(new Runnable() {
                @Override
                public void run() {
                    for (ClientInfo client : activeClients.values()) {
                        try {
                            WsFrame.sendText(client.socket.getOutputStream(), message);
                        } catch (Exception e) {
                            try { client.socket.close(); } catch (Exception ignored) {}
                        }
                    }
                }
            });
        }
    }

    private void handleConnection(final Socket socket) {
        if (threadPool == null || !running) {
            try { socket.close(); } catch (Exception ignored) {}
            return;
        }

        if (activeConnections.incrementAndGet() > MAX_CONCURRENT_CONNECTIONS) {
            activeConnections.decrementAndGet();
            try {
                OutputStream out = socket.getOutputStream();
                sendHttpError(out, 503, "Service Unavailable: Server busy");
            } catch (Exception ignored) {}
            try { socket.close(); } catch (Exception ignored) {}
            return;
        }

        threadPool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    InputStream in = socket.getInputStream();
                    OutputStream out = socket.getOutputStream();

                    BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.ISO_8859_1));
                    String requestLine = reader.readLine();
                    if (requestLine == null || requestLine.isEmpty()) {
                        socket.close();
                        return;
                    }

                    String[] parts = requestLine.split(" ");
                    if (parts.length < 2) {
                        socket.close();
                        return;
                    }

                    String rawUri = parts[1];

                    // Read headers
                    Map<String, String> headers = new HashMap<>();
                    String line;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) {
                        int colon = line.indexOf(':');
                        if (colon > 0) {
                            String key = line.substring(0, colon).trim().toLowerCase();
                            String val = line.substring(colon + 1).trim();
                            headers.put(key, val);
                        }
                    }

                    // Extract path and query params
                    String path = rawUri;
                    String query = "";
                    int qIdx = rawUri.indexOf('?');
                    if (qIdx >= 0) {
                        path = rawUri.substring(0, qIdx);
                        query = rawUri.substring(qIdx + 1);
                    }

                    Map<String, String> queryParams = parseQuery(query);
                    String token = queryParams.get("token");
                    if (token == null) {
                        token = headers.get("x-pairing-token");
                    }
                    if (token == null && headers.containsKey("cookie")) {
                        Map<String, String> cookies = parseCookies(headers.get("cookie"));
                        token = cookies.get("tv_token");
                    }

                    // Check WebSocket upgrade
                    String upgradeHeader = headers.get("upgrade");
                    if (upgradeHeader != null && upgradeHeader.toLowerCase().contains("websocket")) {
                        if (!auth.validate(token)) {
                            sendHttpError(out, 401, "Unauthorized: Invalid pairing token");
                            socket.close();
                            return;
                        }

                        upgradeToWebSocket(socket, in, out, headers.get("sec-websocket-key"), headers.get("user-agent"));
                        return;
                    }

                    // Static Asset Serving
                    if ("/".equals(path) || "/index.html".equals(path)) {
                        serveAsset(out, "web/index.html", "text/html; charset=utf-8", token);
                    } else if ("/app.js".equals(path)) {
                        serveAsset(out, "web/app.js", "application/javascript; charset=utf-8", token);
                    } else if ("/app.css".equals(path)) {
                        serveAsset(out, "web/app.css", "text/css; charset=utf-8", token);
                    } else if ("/manifest.json".equals(path)) {
                        serveAsset(out, "web/manifest.json", "application/manifest+json; charset=utf-8", token);
                    } else if ("/icon.svg".equals(path)) {
                        serveAsset(out, "web/icon.svg", "image/svg+xml", token);
                    } else {
                        sendHttpError(out, 404, "Not Found");
                    }

                    socket.close();
                } catch (Exception e) {
                    try { socket.close(); } catch (Exception ignored) {}
                } finally {
                    activeConnections.decrementAndGet();
                }
            }
        });
    }

    private void upgradeToWebSocket(Socket socket, InputStream in, OutputStream out, String secKey, String userAgent) throws Exception {
        if (secKey == null || secKey.isEmpty()) {
            sendHttpError(out, 400, "Bad Request: Missing Sec-WebSocket-Key");
            socket.close();
            return;
        }

        socket.setSoTimeout(45000);

        String acceptSeed = secKey.trim() + WS_GUID;
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] hash = sha1.digest(acceptSeed.getBytes(StandardCharsets.ISO_8859_1));
        String acceptKey = Base64.encodeToString(hash, Base64.NO_WRAP);

        String response = "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: " + acceptKey + "\r\n\r\n";
        out.write(response.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();

        String clientId = UUID.randomUUID().toString();
        String clientIp = socket.getInetAddress() != null ? socket.getInetAddress().getHostAddress() : "Unknown IP";
        String friendlyDeviceName = parseDeviceName(userAgent);

        ClientInfo clientInfo = new ClientInfo(clientId, clientIp, userAgent, friendlyDeviceName, socket);
        activeClients.put(clientId, clientInfo);
        notifyClientsChanged();

        try {
            // Push initial installed app catalog and audio state
            JSONObject appsMsg = new JSONObject();
            appsMsg.put("type", "APPS_LIST");
            appsMsg.put("apps", AppCatalog.getAppsJsonArray(context));
            android.media.AudioManager am = (android.media.AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                appsMsg.put("isMuted", am.isStreamMute(android.media.AudioManager.STREAM_MUSIC));
            }
            WsFrame.sendText(out, appsMsg.toString());

            // WebSocket message loop
            while (running) {
                WsFrame.Frame frame = WsFrame.readFrame(in);
                if (frame.opcode == WsFrame.OPCODE_CLOSE) {
                    break;
                } else if (frame.opcode == WsFrame.OPCODE_PING) {
                    WsFrame.sendFrame(out, WsFrame.OPCODE_PONG, frame.payload);
                } else if (frame.opcode == WsFrame.OPCODE_TEXT) {
                    String text = frame.getText();
                    JSONObject cmd = new JSONObject(text);
                    if ("HELLO".equals(cmd.optString("type"))) {
                        String reportedName = cmd.optString("deviceName", "").trim();
                        if (!reportedName.isEmpty()) {
                            clientInfo.deviceName = reportedName;
                            notifyClientsChanged();
                        }
                    } else {
                        JSONObject result = dispatcher.execute(cmd);
                        WsFrame.sendText(out, result.toString());
                    }
                }
            }
        } finally {
            activeClients.remove(clientId);
            notifyClientsChanged();
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    private static String parseDeviceName(String ua) {
        if (ua == null) return "Mobile Remote";
        if (ua.contains("iPhone")) return "Apple iPhone";
        if (ua.contains("iPad")) return "Apple iPad";
        if (ua.contains("Pixel")) return "Google Pixel";
        if (ua.contains("Samsung") || ua.contains("SM-")) return "Samsung Phone";
        if (ua.contains("Android")) return "Android Phone";
        if (ua.contains("Macintosh")) return "Apple Mac";
        if (ua.contains("Windows")) return "Windows PC";
        return "Mobile Remote";
    }

    private static Map<String, String> parseCookies(String cookieHeader) {
        Map<String, String> cookies = new HashMap<>();
        if (cookieHeader == null || cookieHeader.isEmpty()) return cookies;
        String[] pairs = cookieHeader.split(";");
        for (String pair : pairs) {
            int idx = pair.indexOf('=');
            if (idx > 0) {
                String k = pair.substring(0, idx).trim();
                String v = pair.substring(idx + 1).trim();
                cookies.put(k, v);
            }
        }
        return cookies;
    }

    private void serveAsset(OutputStream out, String assetPath, String contentType, String token) throws Exception {
        AssetManager am = context.getAssets();
        byte[] body;

        try (InputStream is = am.open(assetPath)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) {
                baos.write(buf, 0, n);
            }
            body = baos.toByteArray();
        } catch (Exception e) {
            sendHttpError(out, 404, "Asset not found: " + assetPath);
            return;
        }

        // Strict token format validation before injecting to prevent XSS
        if ("web/index.html".equals(assetPath)) {
            String safeToken = (token != null && token.matches(SessionAuth.TOKEN_REGEX)) ? token : "";
            String html = new String(body, StandardCharsets.UTF_8);
            html = html.replace("__TOKEN_PLACEHOLDER__", safeToken);
            body = html.getBytes(StandardCharsets.UTF_8);
        }

        String headers = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: " + contentType + "\r\n" +
                "Content-Length: " + body.length + "\r\n" +
                "X-Content-Type-Options: nosniff\r\n" +
                "Cache-Control: no-cache, no-store, must-revalidate\r\n";
        if (token != null && token.matches(SessionAuth.TOKEN_REGEX)) {
            headers += "Set-Cookie: tv_token=" + token + "; Path=/; Max-Age=31536000; SameSite=Lax\r\n";
        }
        headers += "Connection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    private void sendHttpError(OutputStream out, int status, String msg) throws Exception {
        byte[] body = ("<!DOCTYPE html><html><body style='font-family:sans-serif;background:#0B0D13;color:#F8FAFC;padding:40px;text-align:center;'><h2>" +
                status + "</h2><p>" + msg + "</p></body></html>").getBytes(StandardCharsets.UTF_8);
        String resp = "HTTP/1.1 " + status + " Error\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: " + body.length + "\r\n" +
                "Connection: close\r\n\r\n";
        out.write(resp.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    private Map<String, String> parseQuery(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null || query.isEmpty()) return map;
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf('=');
            if (idx > 0) {
                map.put(pair.substring(0, idx), pair.substring(idx + 1));
            }
        }
        return map;
    }

    private void notifyClientsChanged() {
        WeakReference<StatusListener> ref = statusListenerRef;
        if (ref != null) {
            StatusListener listener = ref.get();
            if (listener != null) {
                listener.onClientsChanged(new ArrayList<>(activeClients.values()));
            }
        }
    }
}
