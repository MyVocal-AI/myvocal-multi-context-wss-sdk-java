# MyVocal Multi-Context WSS SDK for Java

Official Java SDK for the MyVocal enterprise Multi-Context WebSocket TTS API.

## Runtime support

- Java 8, 11, 17, 21, and 25
- The SDK JAR is compiled with `--release 8` and Java class major version 52.
- The ZIP contains a thin JAR. Runtime dependencies are listed in
  `DEPENDENCIES.md` and included under `lib/`.

## Local ZIP installation

Install the SDK JAR from the extracted official Release ZIP:

```bash
mvn install:install-file \
  -Dfile=lib/myvocal-multi-context-wss-sdk-1.0.0.jar \
  -DgroupId=ai.myvocal \
  -DartifactId=myvocal-multi-context-wss-sdk \
  -Dversion=1.0.0 \
  -Dpackaging=jar
```

The artifact is not published to Maven Central.

## Minimal flow

```java
import ai.myvocal.multiwss.ConnectionOptions;
import ai.myvocal.multiwss.ContextOptions;
import ai.myvocal.multiwss.MultiContextClient;
import ai.myvocal.multiwss.TtsContext;

import java.util.concurrent.TimeUnit;

public final class Example {
    public static void main(String[] args) throws Exception {
        MultiContextClient client = new MultiContextClient(
                "wss://api.voicelibrary.co",
                System.getenv("MYVOCAL_TTS_API_KEY"),
                System.getenv("MYVOCAL_VOICE_ID"));
        try {
            client.connect(ConnectionOptions.builder()
                    .languageCode("en")
                    .idleTimeout(120)
                    .build());
            TtsContext context = client.createContext(
                    "turn_1",
                    ContextOptions.builder().build());
            context.initialize();
            context.sendText("Hello from MyVocal.", false);
            context.flush();
            context.close();
            context.awaitFinal(60, TimeUnit.SECONDS);
        } finally {
            client.disconnect();
        }
    }
}
```

Normal completion is `flush -> close_context -> is_final`. Calling
`close_context` without `flush` is cancellation and does not implicitly flush
buffered text. An empty final audio field is a valid completion frame.

Customer callbacks run outside OkHttp's reader thread. Callback exceptions are
reported through `onCallbackError` and do not convert successful synthesis into
a transport failure. The SDK never reconnects and never replays text
automatically.
