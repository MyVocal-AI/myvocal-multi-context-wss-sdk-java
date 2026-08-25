package ai.myvocal.multiwss;

import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TTSResponseRoutingTest {
    private MockWebServer server;
    private AtomicReference<WebSocket> serverSocket;
    private MultiContextClient client;

    @Before
    public void setUp() throws Exception {
        serverSocket = new AtomicReference<WebSocket>();
        server = new MockWebServer();
        server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, okhttp3.Response response) {
                serverSocket.set(webSocket);
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                webSocket.close(code, reason);
            }
        }));
        server.start();
        client = new MultiContextClient(
                server.url("/").toString().replaceFirst("^http", "ws"),
                "test-secret",
                "voice-123");
        client.connect(ConnectionOptions.builder().build());
    }

    @After
    public void tearDown() throws Exception {
        client.disconnect();
        server.shutdown();
    }

    @Test
    public void emptyAudioFinalCompletesExactlyOnce() throws Exception {
        final AtomicInteger completes = new AtomicInteger();
        final CountDownLatch latch = new CountDownLatch(1);
        client.createContext("ctx-empty")
                .onComplete(() -> {
                    completes.incrementAndGet();
                    latch.countDown();
                });

        serverSocket.get().send("{\"context_id\":\"ctx-empty\",\"audio\":\"\",\"isFinal\":true}");
        serverSocket.get().send("{\"context_id\":\"ctx-empty\",\"is_final\":true}");

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        Thread.sleep(50L);
        assertEquals(1, completes.get());
        assertEquals(0, client.getActiveContextCount());
    }

    @Test
    public void noAudioFinalStillCompletes() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        client.createContext("ctx-no-audio").onComplete(latch::countDown);
        serverSocket.get().send("{\"context_id\":\"ctx-no-audio\",\"is_final\":true}");
        assertTrue(latch.await(2, TimeUnit.SECONDS));
    }

    @Test
    public void routesAudioTimestampsContextErrorsAndBinaryFrames() throws Exception {
        final AtomicInteger audioCallbacks = new AtomicInteger();
        final AtomicInteger timestampCallbacks = new AtomicInteger();
        final AtomicInteger errors = new AtomicInteger();
        final AtomicReference<byte[]> binary = new AtomicReference<byte[]>();
        final CountDownLatch latch = new CountDownLatch(4);

        client.onBinaryAudio(bytes -> {
            binary.set(bytes);
            latch.countDown();
        });
        client.createContext("ctx-routing")
                .onAudio((audio, isFinal) -> {
                    audioCallbacks.incrementAndGet();
                    latch.countDown();
                })
                .onTimestamps(value -> {
                    timestampCallbacks.incrementAndGet();
                    latch.countDown();
                })
                .onError((code, message) -> {
                    errors.incrementAndGet();
                    latch.countDown();
                });

        serverSocket.get().send("{\"context_id\":\"ctx-routing\",\"audio\":\"AQI=\",\"is_final\":false}");
        serverSocket.get().send("{\"context_id\":\"ctx-routing\",\"timestamps\":{\"chars\":[0,1]}}");
        serverSocket.get().send("{\"context_id\":\"ctx-routing\",\"error\":\"BAD_CONTEXT\",\"message\":\"invalid\"}");
        serverSocket.get().send(okio.ByteString.of("pcm".getBytes(StandardCharsets.UTF_8)));

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(1, audioCallbacks.get());
        assertEquals(1, timestampCallbacks.get());
        assertEquals(1, errors.get());
        assertArrayEquals("pcm".getBytes(StandardCharsets.UTF_8), binary.get());
    }

    @Test
    public void slowAudioCallbackDoesNotBlockAnotherContextFinal() throws Exception {
        final CountDownLatch slowCallbackStarted = new CountDownLatch(1);
        final CountDownLatch releaseSlowCallback = new CountDownLatch(1);
        final CountDownLatch secondContextCompleted = new CountDownLatch(1);

        client.createContext("ctx-slow").onAudio((audio, isFinal) -> {
            slowCallbackStarted.countDown();
            try {
                releaseSlowCallback.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        client.createContext("ctx-fast").onComplete(secondContextCompleted::countDown);

        serverSocket.get().send(
                "{\"context_id\":\"ctx-slow\",\"audio\":\"AQI=\",\"is_final\":false}");
        assertTrue(slowCallbackStarted.await(1, TimeUnit.SECONDS));
        serverSocket.get().send("{\"context_id\":\"ctx-fast\",\"is_final\":true}");

        assertTrue("reader thread was blocked by user callback",
                secondContextCompleted.await(500, TimeUnit.MILLISECONDS));
        releaseSlowCallback.countDown();
    }

    @Test
    public void callbackExceptionDoesNotPoisonSuccessfulCompletion() throws Exception {
        final CountDownLatch callbackError = new CountDownLatch(1);
        final CountDownLatch completed = new CountDownLatch(1);
        client.onCallbackError(error -> callbackError.countDown());
        TtsContext context = client.createContext("ctx-callback")
                .onAudio((audio, isFinal) -> {
                    throw new IllegalStateException("customer callback failed");
                })
                .onComplete(completed::countDown);

        serverSocket.get().send(
                "{\"context_id\":\"ctx-callback\",\"audio\":\"AQI=\",\"is_final\":true}");

        assertTrue(completed.await(2, TimeUnit.SECONDS));
        assertTrue(callbackError.await(2, TimeUnit.SECONDS));
        context.awaitFinal(2, TimeUnit.SECONDS);
    }

    @Test
    public void contextErrorIsStickyAndDoesNotAffectAnotherContext() throws Exception {
        final CountDownLatch healthyCompleted = new CountDownLatch(1);
        TtsContext failed = client.createContext("ctx-failed");
        TtsContext healthy = client.createContext("ctx-healthy")
                .onComplete(healthyCompleted::countDown);

        serverSocket.get().send(
                "{\"context_id\":\"ctx-failed\",\"error\":\"INVALID_REQUEST\","
                        + "\"message\":\"bad context\"}");
        serverSocket.get().send("{\"context_id\":\"ctx-failed\",\"is_final\":true}");
        serverSocket.get().send("{\"context_id\":\"ctx-healthy\",\"is_final\":true}");

        assertTrue(healthyCompleted.await(2, TimeUnit.SECONDS));
        try {
            failed.awaitFinal(2, TimeUnit.SECONDS);
            fail("expected sticky context error");
        } catch (TTSException expected) {
            assertEquals(ConnectionErrorCategory.PROTOCOL, expected.getCategory());
            assertEquals("INVALID_REQUEST", expected.getErrorCode());
        }
        assertNotNull(failed.getTerminalError());
        healthy.awaitFinal(2, TimeUnit.SECONDS);
    }

    @Test
    public void connectionErrorFailsEveryOpenContext() throws Exception {
        TtsContext first = client.createContext("ctx-one");
        TtsContext second = client.createContext("ctx-two");

        serverSocket.get().send(
                "{\"error\":\"UPSTREAM_UNAVAILABLE\",\"message\":\"temporary failure\"}");

        assertTerminalCode(first, "UPSTREAM_UNAVAILABLE");
        assertTerminalCode(second, "UPSTREAM_UNAVAILABLE");
    }

    @Test
    public void controlCallbackStormRemainsBounded() throws Exception {
        final CountDownLatch release = new CountDownLatch(1);
        for (int i = 0; i < 100; i++) {
            client.dispatchControlCallback(() -> {
                try {
                    release.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        assertTrue(client.getPendingControlCallbackCount() <= 16);
        release.countDown();
    }

    @Test
    public void recognizesStringCodeErrorCodeAndNestedErrors() throws Exception {
        assertErrorShape(
                "ctx-string",
                "{\"context_id\":\"ctx-string\",\"error\":\"STRING_ERROR\"}",
                "STRING_ERROR");
        assertErrorShape(
                "ctx-code",
                "{\"context_id\":\"ctx-code\",\"code\":\"CODE_ERROR\"}",
                "CODE_ERROR");
        assertErrorShape(
                "ctx-error-code",
                "{\"context_id\":\"ctx-error-code\",\"error_code\":\"ERROR_CODE\"}",
                "ERROR_CODE");
        assertErrorShape(
                "ctx-nested",
                "{\"context_id\":\"ctx-nested\","
                        + "\"error\":{\"code\":\"NESTED_ERROR\",\"message\":\"nested\"}}",
                "NESTED_ERROR");
    }

    @Test
    public void binaryJsonFrameIsParsedInsteadOfReportedAsAudio() throws Exception {
        final CountDownLatch completed = new CountDownLatch(1);
        final AtomicInteger binaryAudio = new AtomicInteger();
        client.onBinaryAudio(bytes -> binaryAudio.incrementAndGet());
        TtsContext context = client.createContext("ctx-binary-json")
                .onComplete(completed::countDown);

        serverSocket.get().send(okio.ByteString.encodeUtf8(
                "{\"context_id\":\"ctx-binary-json\",\"is_final\":true}"));

        assertTrue(completed.await(2, TimeUnit.SECONDS));
        context.awaitFinal(2, TimeUnit.SECONDS);
        assertEquals(0, binaryAudio.get());
    }

    @Test
    public void lateAudioAfterFinalIsIgnored() throws Exception {
        final AtomicInteger audioCallbacks = new AtomicInteger();
        final CountDownLatch completeStarted = new CountDownLatch(1);
        final CountDownLatch releaseComplete = new CountDownLatch(1);
        TtsContext context = client.createContext("ctx-late")
                .onAudio((audio, isFinal) -> audioCallbacks.incrementAndGet())
                .onComplete(() -> {
                    completeStarted.countDown();
                    try {
                        releaseComplete.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                });

        serverSocket.get().send("{\"context_id\":\"ctx-late\",\"is_final\":true}");
        assertTrue(completeStarted.await(1, TimeUnit.SECONDS));
        serverSocket.get().send(
                "{\"context_id\":\"ctx-late\",\"audio\":\"AQI=\",\"is_final\":false}");
        Thread.sleep(50L);

        assertEquals(0, audioCallbacks.get());
        releaseComplete.countDown();
        context.awaitFinal(2, TimeUnit.SECONDS);
        Thread.sleep(50L);
        assertEquals(0, audioCallbacks.get());
    }

    private static void assertTerminalCode(TtsContext context, String expectedCode)
            throws InterruptedException {
        try {
            context.awaitFinal(2, TimeUnit.SECONDS);
            fail("expected terminal error");
        } catch (TTSException expected) {
            assertEquals(expectedCode, expected.getErrorCode());
        }
    }

    private void assertErrorShape(
            String contextId,
            String payload,
            String expectedCode) throws Exception {
        TtsContext context = client.createContext(contextId);
        serverSocket.get().send(payload);
        assertTerminalCode(context, expectedCode);
    }
}

