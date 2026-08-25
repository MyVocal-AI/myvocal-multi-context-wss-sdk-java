package ai.myvocal.multiwss;

import ai.myvocal.multiwss.callback.AudioCallback;
import ai.myvocal.multiwss.callback.CompleteCallback;
import ai.myvocal.multiwss.callback.DecodedAudioCallback;
import ai.myvocal.multiwss.callback.ErrorCallback;
import ai.myvocal.multiwss.callback.TimestampCallback;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One independent synthesis context on a shared multi-context connection.
 */
public class TtsContext {
    private final String contextId;
    private final MultiContextClient client;
    private final ContextOptions options;
    private final List<byte[]> audioChunks =
            Collections.synchronizedList(new ArrayList<byte[]>());
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean closeRequested = new AtomicBoolean(false);
    private final AtomicBoolean completed = new AtomicBoolean(false);
    private final AtomicBoolean terminal = new AtomicBoolean(false);
    private final AtomicReference<TTSException> terminalError =
            new AtomicReference<TTSException>();
    private final CountDownLatch completionLatch = new CountDownLatch(1);
    private final CallbackDispatcher callbacks;

    private volatile AudioCallback audioCallback;
    private volatile DecodedAudioCallback decodedAudioCallback;
    private volatile ErrorCallback errorCallback;
    private volatile CompleteCallback completeCallback;
    private volatile TimestampCallback timestampCallback;

    TtsContext(String contextId, MultiContextClient client, ContextOptions options) {
        this.contextId = contextId;
        this.client = client;
        this.options = options == null ? ContextOptions.builder().build() : options;
        this.callbacks = new CallbackDispatcher(
                "myvocal-wss-context-" + contextId,
                64,
                new java.util.function.Consumer<Throwable>() {
                    @Override
                    public void accept(Throwable failure) {
                        TtsContext.this.client.reportCallbackError(failure);
                    }
                });
    }

    /**
     * Retained for source compatibility with older package-local callers.
     */
    TtsContext(String contextId, MultiContextClient client) {
        this(contextId, client, ContextOptions.builder().build());
    }

    public String getContextId() {
        return contextId;
    }

    public TtsContext onAudio(AudioCallback callback) {
        audioCallback = callback;
        return this;
    }

    public TtsContext onDecodedAudio(DecodedAudioCallback callback) {
        decodedAudioCallback = callback;
        return this;
    }

    public TtsContext onError(ErrorCallback callback) {
        errorCallback = callback;
        return this;
    }

    public TtsContext onComplete(CompleteCallback callback) {
        completeCallback = callback;
        return this;
    }

    public TtsContext onTimestamps(TimestampCallback callback) {
        timestampCallback = callback;
        return this;
    }

    public void initialize() {
        ensureWritable();
        if (!initialized.compareAndSet(false, true)) {
            throw new IllegalStateException("Context is already initialized");
        }
        client.sendMessage(options.toInitializationJson(contextId));
    }

    public void sendText(String text, boolean flush) {
        ensureWritable();
        if (text == null) {
            throw new IllegalArgumentException("text must not be null");
        }
        JSONObject message = baseMessage();
        message.put("text", text);
        message.put("flush", flush);
        client.sendMessage(message);
    }

    public void flush() {
        ensureWritable();
        JSONObject message = baseMessage();
        message.put("flush", true);
        client.sendMessage(message);
    }

    /**
     * Sends an application-level keepalive for this context.
     */
    public void keepAlive() {
        ensureWritable();
        JSONObject message = baseMessage();
        message.put("text", "");
        client.sendMessage(message);
    }

    /**
     * Backward-compatible alias for flush.
     */
    public void endInput() {
        flush();
    }

    /**
     * Requests context closure. The context remains routable until the server's
     * final frame arrives.
     */
    public void close() {
        if (completed.get() || !closeRequested.compareAndSet(false, true)) {
            return;
        }
        JSONObject message = baseMessage();
        message.put("close_context", true);
        client.sendMessage(message);
    }

    public boolean awaitCompletion(long timeout, TimeUnit unit) throws InterruptedException {
        return completionLatch.await(timeout, unit);
    }

    public void awaitFinal(long timeout, TimeUnit unit)
            throws InterruptedException, TTSException {
        if (!completionLatch.await(timeout, unit)) {
            throw new TTSException(
                    ConnectionErrorCategory.TIMEOUT,
                    "Context did not complete within the requested timeout");
        }
        TTSException failure = terminalError.get();
        if (failure != null) {
            throw failure;
        }
    }

    public TTSException getTerminalError() {
        return terminalError.get();
    }

    public List<byte[]> getAudioChunks() {
        synchronized (audioChunks) {
            return new ArrayList<byte[]>(audioChunks);
        }
    }

    void handleAudio(String audioBase64, boolean isFinal) {
        if (terminal.get()) {
            return;
        }
        try {
            byte[] decoded = audioBase64.isEmpty()
                    ? new byte[0]
                    : Base64.getDecoder().decode(audioBase64);
            if (decoded.length > 0) {
                audioChunks.add(decoded);
            }
            final AudioCallback raw = audioCallback;
            final DecodedAudioCallback bytes = decodedAudioCallback;
            final byte[] callbackBytes = decoded;
            final String callbackBase64 = audioBase64;
            if (raw != null) {
                dispatchDataCallback(new Runnable() {
                    @Override
                    public void run() {
                        raw.onAudio(callbackBase64, isFinal);
                    }
                });
            }
            if (bytes != null) {
                dispatchDataCallback(new Runnable() {
                    @Override
                    public void run() {
                        bytes.onAudio(callbackBytes, isFinal);
                    }
                });
            }
            if (isFinal) {
                handleFinal();
            }
        } catch (IllegalArgumentException invalidBase64) {
            handleError("DECODE_ERROR", "Server returned invalid Base64 audio");
        }
    }

    void handleFinal() {
        if (!terminal.compareAndSet(false, true)) {
            return;
        }
        completed.set(true);
        final CompleteCallback callback = completeCallback;
        if (!callbacks.dispatch(new Runnable() {
            @Override
            public void run() {
                try {
                    if (callback != null) {
                        callback.onComplete();
                    }
                } finally {
                    completionLatch.countDown();
                    client.removeContext(contextId);
                    callbacks.shutdown();
                }
            }
        })) {
            completionLatch.countDown();
            client.removeContext(contextId);
            callbacks.shutdownNow();
        }
    }

    void handleTimestamps(Object timestamps) {
        if (terminal.get()) {
            return;
        }
        final TimestampCallback callback = timestampCallback;
        if (callback != null) {
            final Object value = timestamps;
            dispatchDataCallback(new Runnable() {
                @Override
                public void run() {
                    callback.onTimestamps(value);
                }
            });
        }
    }

    void handleError(String errorCode, String message) {
        handleError(new TTSException(
                ConnectionErrorCategory.PROTOCOL,
                null,
                errorCode,
                message,
                null));
    }

    void handleError(final TTSException error) {
        if (!terminal.compareAndSet(false, true)) {
            return;
        }
        terminalError.set(error);
        final ErrorCallback callback = errorCallback;
        if (!callbacks.dispatch(new Runnable() {
            @Override
            public void run() {
                try {
                    if (callback != null) {
                        callback.onError(error.getErrorCode(), error.getMessage());
                    }
                } finally {
                    completionLatch.countDown();
                    client.removeContext(contextId);
                    callbacks.shutdown();
                }
            }
        })) {
            completionLatch.countDown();
            client.removeContext(contextId);
            callbacks.shutdownNow();
        }
    }

    public boolean isClosed() {
        return closeRequested.get() || completed.get();
    }

    public boolean isComplete() {
        return completed.get();
    }

    private JSONObject baseMessage() {
        JSONObject message = new JSONObject();
        message.put("context_id", contextId);
        return message;
    }

    private void ensureWritable() {
        if (closeRequested.get() || terminal.get()) {
            throw new IllegalStateException("Context is closed");
        }
    }

    private void dispatchDataCallback(Runnable callback) {
        if (!callbacks.dispatch(callback)) {
            handleError(new TTSException(
                    ConnectionErrorCategory.CONTEXT,
                    null,
                    "CALLBACK_QUEUE_FULL",
                    "Context callback queue is full",
                    null));
        }
    }
}

