package ai.myvocal.multiwss;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

public class WireContractTest {
    @Test
    public void emitsCurrentIdleTimeoutAndCanonicalFormatNames() {
        Map<String, String> query = ConnectionOptions.builder()
                .outputFormat("pcm_16000")
                .idleTimeout(120)
                .build()
                .toQueryMap();

        assertEquals("120", query.get("idleTimeout"));
        assertEquals("pcm_16000", query.get("format"));
        assertFalse(query.containsKey("inactivity_timeout"));
        assertFalse(query.containsKey("output_format"));
    }

    @Test
    public void rejectsValuesOutsidePublishedConnectionContract() {
        assertRejected(() -> ConnectionOptions.builder().priority("auto").build());
        assertRejected(() -> ConnectionOptions.builder().modelId("eleven_v3").build());
        assertRejected(() -> ConnectionOptions.builder().format("wav").build());
        assertRejected(() -> ConnectionOptions.builder().languageCode("EN").build());
        assertRejected(() -> ConnectionOptions.builder()
                .extension("unverified_parameter", "value")
                .build());
    }

    private static void assertRejected(Runnable action) {
        try {
            action.run();
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected local contract rejection.
        }
    }
}
