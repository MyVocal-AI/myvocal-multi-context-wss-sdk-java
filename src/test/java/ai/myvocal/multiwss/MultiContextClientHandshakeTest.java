package ai.myvocal.multiwss;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class MultiContextClientHandshakeTest {
    private MockWebServer server;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @After
    public void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    public void connectsWithApiKeyAndTypedQuery() throws Exception {
        server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
            @Override
            public void onMessage(okhttp3.WebSocket webSocket, String text) {
            }

            @Override
            public void onMessage(okhttp3.WebSocket webSocket, ByteString bytes) {
            }

            @Override
            public void onClosing(okhttp3.WebSocket webSocket, int code, String reason) {
                webSocket.close(code, reason);
            }
        }));

        MultiContextClient client = new MultiContextClient(wsBaseUrl(), "test-secret-value", "voice-123");
        client.connect(ConnectionOptions.builder()
                .modelId("flash_v2_5")
                .format("pcm_8000")
                .languageCode("en")
                .idleTimeout(120)
                .seed(42)
                .autoMode(true)
                .build());

        RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("test-secret-value", request.getHeader("api-key"));
        assertTrue(request.getPath().startsWith(
                "/enterprise/v1/tts/voice-123/websocket/multi?"));
        assertTrue(request.getRequestUrl().queryParameterNames().contains("idleTimeout"));
        assertEquals("120", request.getRequestUrl().queryParameter("idleTimeout"));
        assertEquals("pcm_8000", request.getRequestUrl().queryParameter("format"));
        assertTrue(client.isConnected());
        client.disconnect();
    }

    @Test
    public void exposesRejectedUpgradeStatusWithoutLeakingKey() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(401).setBody("not authorized"));
        String secret = "must-never-appear";
        MultiContextClient client = new MultiContextClient(wsBaseUrl(), secret, "voice-123");

        try {
            client.connect(ConnectionOptions.builder().build());
            fail("Expected TTSException");
        } catch (TTSException error) {
            assertEquals(ConnectionErrorCategory.AUTHENTICATION, error.getCategory());
            assertEquals(Integer.valueOf(401), error.getHttpStatus());
            assertFalse(error.getMessage().contains(secret));
            assertFalse(error.toString().contains(secret));
        } finally {
            client.disconnect();
        }
    }

    @Test
    public void reportsBoundedConnectTimeout() throws Exception {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        MultiContextClient client = new MultiContextClient(wsBaseUrl(), "test-secret-value", "voice-123", 150L);

        try {
            client.connect(ConnectionOptions.builder().build());
            fail("Expected timeout");
        } catch (TTSException error) {
            assertEquals(ConnectionErrorCategory.TIMEOUT, error.getCategory());
        } finally {
            client.disconnect();
        }
    }

    private String wsBaseUrl() {
        return server.url("/").toString().replaceFirst("^http", "ws");
    }
}

