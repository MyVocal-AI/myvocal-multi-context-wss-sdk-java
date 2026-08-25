package ai.myvocal.multiwss;

import ai.myvocal.multiwss.callback.BinaryAudioCallback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.json.JSONObject;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Java 8 multi-context WebSocket TTS client.
 */
public class MultiContextClient implements AutoCloseable {
    private static final int MAX_CONTEXTS = 5;
    private static final long DEFAULT_CONNECT_TIMEOUT_MILLIS = 10000L;

    private final String baseUrl;
    private final String apiKey;
    private final String voiceId;
    private final long connectTimeoutMillis;
    private final OkHttpClient httpClient;
    private final boolean ownsHttpClient;
    private final Map<String, TtsContext> contexts =
            new ConcurrentHashMap<String, TtsContext>();
    private final CallbackDispatcher controlCallbacks =
            new CallbackDispatcher("myvocal-wss-control-callback", 16, null);
    private final CallbackDispatcher binaryCallbacks =
            new CallbackDispatcher(
                    "myvocal-wss-binary-callback",
                    64,
                    new Consumer<Throwable>() {
                        @Override
                        public void accept(Throwable failure) {
                            reportCallbackError(failure);
                        }
                    });

    private volatile WebSocket webSocket;
    private volatile boolean connected;
    private volatile boolean disconnecting;
    private volatile BinaryAudioCallback binaryAudioCallback;
    private volatile Consumer<Throwable> callbackErrorHandler;
    private volatile TTSException connectionError;
    private volatile CountDownLatch socketClosedLatch = new CountDownLatch(0);

    public MultiContextClient(String baseUrl, String apiKey, String voiceId) {
        this(baseUrl, apiKey, voiceId, DEFAULT_CONNECT_TIMEOUT_MILLIS);
    }

    public MultiContextClient(String baseUrl, String apiKey, String voiceId, long connectTimeoutMillis) {
        this(baseUrl, apiKey, voiceId, connectTimeoutMillis,
                new OkHttpClient.Builder()
                        .readTimeout(0, TimeUnit.MILLISECONDS)
                        .pingInterval(20, TimeUnit.SECONDS)
                        .build(),
                true);
    }

    MultiContextClient(
            String baseUrl,
            String apiKey,
            String voiceId,
            long connectTimeoutMillis,
            OkHttpClient httpClient,
            boolean ownsHttpClient) {
        requireText("baseUrl", baseUrl);
        requireText("apiKey", apiKey);
        requireText("voiceId", voiceId);
        if (connectTimeoutMillis < 1) {
            throw new IllegalArgumentException("connectTimeoutMillis must be positive");
        }
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.voiceId = voiceId;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.httpClient = httpClient;
        this.ownsHttpClient = ownsHttpClient;
    }

    /**
     * Legacy map-based connection entry.
     */
    public void connect(Map<String, String> params) throws TTSException {
        connect(ConnectionOptions.fromLegacyMap(params));
    }

    public synchronized void connect(ConnectionOptions options) throws TTSException {
        if (connected || webSocket != null) {
            throw new IllegalStateException("Already connected or connecting");
        }
        if (options == null) {
            options = ConnectionOptions.builder().build();
        }

        final CountDownLatch resultLatch = new CountDownLatch(1);
        final AtomicReference<TTSException> failure = new AtomicReference<TTSException>();
        disconnecting = false;
        connectionError = null;
        socketClosedLatch = new CountDownLatch(1);

        Request request = new Request.Builder()
                .url(buildEndpointUrl(options))
                .header("api-key", apiKey)
                .build();

        webSocket = httpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket socket, Response response) {
                connected = true;
                resultLatch.countDown();
            }

            @Override
            public void onMessage(WebSocket socket, String text) {
                handleMessage(text);
            }

            @Override
            public void onMessage(WebSocket socket, ByteString bytes) {
                if (looksLikeJson(bytes)) {
                    handleMessage(bytes.string(StandardCharsets.UTF_8));
                    return;
                }
                final BinaryAudioCallback callback = binaryAudioCallback;
                if (callback != null) {
                    final byte[] audio = bytes.toByteArray();
                    if (!binaryCallbacks.dispatch(new Runnable() {
                        @Override
                        public void run() {
                            callback.onAudio(audio);
                        }
                    })) {
                        reportCallbackError(new IllegalStateException(
                                "Binary audio callback queue is full"));
                    }
                }
            }

            @Override
            public void onClosing(WebSocket socket, int code, String reason) {
                socket.close(code, reason);
            }

            @Override
            public void onClosed(WebSocket socket, int code, String reason) {
                connected = false;
                socketClosedLatch.countDown();
                if (resultLatch.getCount() > 0) {
                    failure.compareAndSet(null, new TTSException(
                            ConnectionErrorCategory.PROTOCOL,
                            "WebSocket closed before the connection opened (code " + code + ")"));
                    resultLatch.countDown();
                } else if (!disconnecting && !contexts.isEmpty()) {
                    failConnection(new TTSException(
                            ConnectionErrorCategory.NETWORK,
                            null,
                            "SOCKET_CLOSED",
                            "WebSocket closed before all contexts completed (code " + code + ")",
                            null));
                }
            }

            @Override
            public void onFailure(WebSocket socket, Throwable error, Response response) {
                connected = false;
                socketClosedLatch.countDown();
                TTSException mapped = toConnectionException(error, response);
                failure.compareAndSet(null, mapped);
                if (resultLatch.getCount() == 0) {
                    failConnection(mapped);
                }
                resultLatch.countDown();
            }
        });

        try {
            if (!resultLatch.await(connectTimeoutMillis, TimeUnit.MILLISECONDS)) {
                WebSocket pending = webSocket;
                if (pending != null) {
                    pending.cancel();
                }
                webSocket = null;
                shutdownOwnedHttpClient();
                throw new TTSException(
                        ConnectionErrorCategory.TIMEOUT,
                        "WebSocket connection did not open within " + connectTimeoutMillis + " ms");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            WebSocket pending = webSocket;
            if (pending != null) {
                pending.cancel();
            }
            webSocket = null;
            shutdownOwnedHttpClient();
            throw new TTSException(
                    ConnectionErrorCategory.UNKNOWN,
                    null,
                    "Interrupted while opening WebSocket",
                    interrupted);
        }

        TTSException connectionFailure = failure.get();
        if (connectionFailure != null) {
            webSocket = null;
            shutdownOwnedHttpClient();
            throw connectionFailure;
        }
        if (!connected) {
            webSocket = null;
            shutdownOwnedHttpClient();
            throw new TTSException(
                    ConnectionErrorCategory.PROTOCOL,
                    "WebSocket did not reach the open state");
        }
    }

    public MultiContextClient onBinaryAudio(BinaryAudioCallback callback) {
        binaryAudioCallback = callback;
        return this;
    }

    public MultiContextClient onCallbackError(Consumer<Throwable> callback) {
        callbackErrorHandler = callback;
        return this;
    }

    public TtsContext createContext(String contextId) {
        return createContext(contextId, ContextOptions.builder().build());
    }

    public TtsContext createContext(String contextId, ContextOptions options) {
        if (!connected) {
            throw new IllegalStateException("Not connected");
        }
        requireText("contextId", contextId);
        if (contexts.size() >= MAX_CONTEXTS) {
            throw new IllegalStateException("Maximum contexts (" + MAX_CONTEXTS + ") reached");
        }
        if (contexts.containsKey(contextId)) {
            throw new IllegalArgumentException("Context already exists: " + contextId);
        }
        TtsContext context = new TtsContext(contextId, this, options);
        contexts.put(contextId, context);
        return context;
    }

    public TtsContext getContext(String contextId) {
        return contexts.get(contextId);
    }

    void removeContext(String contextId) {
        contexts.remove(contextId);
    }

    void sendMessage(JSONObject message) {
        TTSException failure = connectionError;
        if (failure != null) {
            throw new IllegalStateException(failure.getMessage(), failure);
        }
        WebSocket socket = webSocket;
        if (!connected || socket == null) {
            throw new IllegalStateException("Not connected");
        }
        if (!socket.send(message.toString())) {
            throw new IllegalStateException("WebSocket rejected the outgoing message");
        }
    }

    private String buildEndpointUrl(ConnectionOptions options) {
        String normalized = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
        String httpBase;
        if (normalized.startsWith("wss://")) {
            httpBase = "https://" + normalized.substring(6);
        } else if (normalized.startsWith("ws://")) {
            httpBase = "http://" + normalized.substring(5);
        } else {
            throw new IllegalArgumentException("baseUrl must use ws:// or wss://");
        }

        HttpUrl parsed = HttpUrl.parse(httpBase);
        if (parsed == null) {
            throw new IllegalArgumentException("Invalid baseUrl");
        }
        HttpUrl.Builder builder = parsed.newBuilder()
                .addPathSegments("enterprise/v1/tts")
                .addPathSegment(voiceId)
                .addPathSegments("websocket/multi");
        for (Map.Entry<String, String> item : options.toQueryMap().entrySet()) {
            builder.addQueryParameter(item.getKey(), item.getValue());
        }
        String httpUrl = builder.build().toString();
        return httpUrl.startsWith("https://")
                ? "wss://" + httpUrl.substring(8)
                : "ws://" + httpUrl.substring(7);
    }

    void handleMessage(String message) {
        try {
            JSONObject data = new JSONObject(message);
            String contextId = data.optString("context_id", null);
            if (contextId == null) {
                contextId = data.optString("contextId", null);
            }

            TTSException protocolError = protocolErrorFrom(data, contextId);
            if (protocolError != null) {
                TTSException error = new TTSException(
                        protocolError.getCategory(),
                        protocolError.getHttpStatus(),
                        protocolError.getErrorCode(),
                        protocolError.getMessage(),
                        protocolError.getCause());
                TtsContext context = contextId == null ? null : contexts.get(contextId);
                if (context != null) {
                    context.handleError(error);
                } else {
                    failConnection(error);
                }
                return;
            }

            TtsContext context = contextId == null ? null : contexts.get(contextId);
            if (context == null) {
                return;
            }
            boolean isFinal = data.optBoolean("is_final", data.optBoolean("isFinal", false));
            if (data.has("audio") && !data.isNull("audio")) {
                context.handleAudio(data.optString("audio", ""), isFinal);
            } else if (isFinal) {
                context.handleFinal();
            }
            if (data.has("timestamps") && !data.isNull("timestamps")) {
                context.handleTimestamps(data.get("timestamps"));
            }
        } catch (RuntimeException invalidMessage) {
            reportCallbackError(new IllegalArgumentException(
                    "Server returned malformed JSON", invalidMessage));
        }
    }

    public synchronized void disconnect() {
        disconnecting = true;
        WebSocket socket = webSocket;
        if (socket != null && connected) {
            JSONObject closeSocket = new JSONObject();
            closeSocket.put("close_socket", true);
            socket.send(closeSocket.toString());
            socket.close(1000, "client disconnect");
            try {
                if (!socketClosedLatch.await(200, TimeUnit.MILLISECONDS)) {
                    socket.cancel();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                socket.cancel();
            }
        } else if (socket != null) {
            socket.cancel();
        }
        connected = false;
        webSocket = null;
        TTSException closed = connectionError == null
                ? new TTSException(
                        ConnectionErrorCategory.NETWORK,
                        null,
                        "CLIENT_CLOSED",
                        "Client disconnected before context completion",
                        null)
                : connectionError;
        for (TtsContext context : contexts.values()) {
            context.handleError(closed);
        }
        contexts.clear();
        binaryCallbacks.shutdown();
        controlCallbacks.shutdown();
        shutdownOwnedHttpClient();
    }

    @Override
    public void close() {
        disconnect();
    }

    public boolean isConnected() {
        return connected;
    }

    public int getActiveContextCount() {
        return contexts.size();
    }

    boolean dispatchControlCallback(Runnable callback) {
        return controlCallbacks.dispatch(callback);
    }

    int getPendingControlCallbackCount() {
        return controlCallbacks.pendingCount();
    }

    void reportCallbackError(final Throwable failure) {
        final Consumer<Throwable> handler = callbackErrorHandler;
        if (handler == null) {
            return;
        }
        dispatchControlCallback(new Runnable() {
            @Override
            public void run() {
                try {
                    handler.accept(failure);
                } catch (Throwable ignored) {
                    // Callback failures never escape into the transport reader.
                }
            }
        });
    }

    private void failConnection(TTSException error) {
        if (connectionError == null) {
            connectionError = error;
        }
        TTSException sticky = connectionError;
        for (TtsContext context : contexts.values()) {
            context.handleError(sticky);
        }
    }

    private void shutdownOwnedHttpClient() {
        if (ownsHttpClient) {
            httpClient.dispatcher().executorService().shutdown();
            httpClient.connectionPool().evictAll();
        }
    }

    private static TTSException toConnectionException(Throwable error, Response response) {
        if (response != null) {
            int status = response.code();
            ConnectionErrorCategory category = status == 401 || status == 403
                    ? ConnectionErrorCategory.AUTHENTICATION
                    : status >= 500
                    ? ConnectionErrorCategory.SERVER
                    : ConnectionErrorCategory.PROTOCOL;
            return new TTSException(
                    category,
                    status,
                    "WebSocket upgrade rejected with HTTP " + status,
                    error);
        }
        if (error instanceof SSLException) {
            return new TTSException(ConnectionErrorCategory.TLS, null,
                    "TLS handshake failed", error);
        }
        if (error instanceof SocketTimeoutException) {
            return new TTSException(ConnectionErrorCategory.TIMEOUT, null,
                    "WebSocket connection timed out", error);
        }
        if (error instanceof UnknownHostException) {
            return new TTSException(ConnectionErrorCategory.DNS, null,
                    "WebSocket host could not be resolved", error);
        }
        if (error instanceof IOException) {
            return new TTSException(ConnectionErrorCategory.NETWORK, null,
                    "WebSocket network connection failed", error);
        }
        return new TTSException(ConnectionErrorCategory.UNKNOWN, null,
                "WebSocket connection failed", error);
    }

    private static TTSException protocolErrorFrom(
            JSONObject data,
            String contextId) {
        Object rawError = data.opt("error");
        JSONObject nested = rawError instanceof JSONObject
                ? (JSONObject) rawError
                : null;
        String code = rawError instanceof String ? (String) rawError : null;
        if (code == null || code.isEmpty()) {
            code = nullableString(data.opt("code"));
        }
        if (code == null || code.isEmpty()) {
            code = nullableString(data.opt("error_code"));
        }
        if ((code == null || code.isEmpty()) && nested != null) {
            code = nullableString(nested.opt("code"));
        }
        if (code == null || code.isEmpty()) {
            return null;
        }
        String message = nullableString(data.opt("message"));
        if (message == null || message.isEmpty()) {
            message = nullableString(data.opt("error_message"));
        }
        if ((message == null || message.isEmpty()) && nested != null) {
            message = nullableString(nested.opt("message"));
        }
        if (message == null || message.isEmpty()) {
            message = code;
        }
        return new TTSException(
                ConnectionErrorCategory.PROTOCOL,
                null,
                code,
                message,
                null);
    }

    private static String nullableString(Object value) {
        if (value == null || value == JSONObject.NULL) {
            return null;
        }
        String result = String.valueOf(value);
        return result.isEmpty() ? null : result;
    }

    private static boolean looksLikeJson(ByteString bytes) {
        byte[] value = bytes.toByteArray();
        for (byte item : value) {
            char character = (char) (item & 0xff);
            if (Character.isWhitespace(character)) {
                continue;
            }
            return character == '{' || character == '[';
        }
        return false;
    }

    private static void requireText(String name, String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}

