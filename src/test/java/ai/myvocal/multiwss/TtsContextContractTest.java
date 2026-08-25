package ai.myvocal.multiwss;

import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class TtsContextContractTest {
    private MockWebServer server;
    private LinkedBlockingQueue<String> clientMessages;
    private MultiContextClient client;

    @Before
    public void setUp() throws Exception {
        clientMessages = new LinkedBlockingQueue<String>();
        server = new MockWebServer();
        server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
            @Override
            public void onMessage(WebSocket webSocket, String text) {
                clientMessages.add(text);
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
        if (client != null) {
            client.disconnect();
        }
        server.shutdown();
    }

    @Test
    public void sendsCompleteInitializationPayload() throws Exception {
        Map<String, Object> generation = new LinkedHashMap<String, Object>();
        generation.put("chunk_length_schedule", Arrays.asList(120, 160, 250));

        ContextOptions options = ContextOptions.builder()
                .initialText(" ")
                .previousText("Previous sentence.")
                .nextText("Next sentence.")
                .voiceSettings(VoiceSettings.builder()
                        .stability(0.5)
                        .similarityBoost(0.75)
                        .style(0.3)
                        .useSpeakerBoost(false)
                        .build())
                .generationConfig(generation)
                .build();

        client.createContext("ctx-init", options).initialize();
        JSONObject json = takeJson();
        assertEquals("ctx-init", json.getString("context_id"));
        assertEquals(" ", json.getString("text"));
        assertEquals("Previous sentence.", json.getString("previous_text"));
        assertEquals("Next sentence.", json.getString("next_text"));
        JSONObject voice = json.getJSONObject("voice_settings");
        assertEquals(0.5, voice.getDouble("stability"), 0.0001);
        assertEquals(0.75, voice.getDouble("similarity_boost"), 0.0001);
        assertEquals(0.3, voice.getDouble("style"), 0.0001);
        assertFalse(voice.getBoolean("use_speaker_boost"));
        assertTrue(json.getJSONObject("generation_config")
                .getJSONArray("chunk_length_schedule").length() == 3);
    }

    @Test
    public void sendsTextFlushKeepAliveAndCloseInDocumentedShape() throws Exception {
        TtsContext context = client.createContext("ctx-flow");
        context.sendText("Hello", false);
        context.flush();
        context.keepAlive();
        context.close();

        JSONObject text = takeJson();
        assertEquals("ctx-flow", text.getString("context_id"));
        assertEquals("Hello", text.getString("text"));
        assertFalse(text.getBoolean("flush"));
        assertFalse(text.has("type"));

        JSONObject flush = takeJson();
        assertTrue(flush.getBoolean("flush"));
        assertFalse(flush.has("text"));

        JSONObject keepAlive = takeJson();
        assertEquals("", keepAlive.getString("text"));
        assertFalse(keepAlive.has("flush"));

        JSONObject close = takeJson();
        assertTrue(close.getBoolean("close_context"));
        assertEquals(1, client.getActiveContextCount());
    }

    @Test
    public void endInputIsBackwardCompatibleFlushAlias() throws Exception {
        TtsContext context = client.createContext("ctx-eos");
        context.endInput();
        JSONObject message = takeJson();
        assertTrue(message.getBoolean("flush"));
        assertFalse(message.has("type"));
    }

    @Test
    public void disconnectSendsCloseSocket() throws Exception {
        client.disconnect();
        JSONObject message = takeJson();
        assertTrue(message.getBoolean("close_socket"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsStyleOutsideRange() {
        VoiceSettings.builder().style(1.01).build();
    }

    @Test(expected = IllegalStateException.class)
    public void enforcesFiveContextLimit() {
        for (int i = 0; i < 6; i++) {
            client.createContext("ctx-" + i);
        }
    }

    private JSONObject takeJson() throws Exception {
        String text = clientMessages.poll(2, TimeUnit.SECONDS);
        assertNotNull(text);
        return new JSONObject(text);
    }
}

