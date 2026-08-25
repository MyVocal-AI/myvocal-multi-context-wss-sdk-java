import ai.myvocal.multiwss.ConnectionOptions;
import ai.myvocal.multiwss.ContextOptions;
import ai.myvocal.multiwss.MultiContextClient;
import ai.myvocal.multiwss.TtsContext;

import java.util.concurrent.TimeUnit;

public final class BasicExample {
    private BasicExample() {
    }

    public static void main(String[] args) throws Exception {
        String apiKey = requireEnvironment("MYVOCAL_TTS_API_KEY");
        String voiceId = requireEnvironment("MYVOCAL_VOICE_ID");
        MultiContextClient client = new MultiContextClient(
                "wss://api.voicelibrary.co",
                apiKey,
                voiceId);
        try {
            client.connect(ConnectionOptions.builder()
                    .format("pcm_16000")
                    .languageCode("en")
                    .idleTimeout(120)
                    .build());
            TtsContext context = client.createContext(
                    "example",
                    ContextOptions.builder().build());
            context.onDecodedAudio((audio, isFinal) ->
                    System.out.println("Audio bytes: " + audio.length));
            context.initialize();
            context.sendText("Hello from MyVocal.", false);
            context.flush();
            context.close();
            context.awaitFinal(60, TimeUnit.SECONDS);
        } finally {
            client.disconnect();
        }
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }
}
