package ai.myvocal.multiwss;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class ConnectionOptionsTest {
    @Test
    public void exposesEveryDocumentedConnectionParameter() {
        ConnectionOptions options = ConnectionOptions.builder()
                .priority("dedicated_concurrency")
                .modelId("flash_v2_5")
                .format("pcm_8000")
                .languageCode("en")
                .idleTimeout(120)
                .timestamps(true)
                .directStreaming(false)
                .seed(4294967295L)
                .autoMode(true)
                .build();

        Map<String, String> query = options.toQueryMap();
        assertEquals("dedicated_concurrency", query.get("priority"));
        assertEquals("flash_v2_5", query.get("model_id"));
        assertEquals("pcm_8000", query.get("format"));
        assertEquals("en", query.get("language_code"));
        assertEquals("120", query.get("idleTimeout"));
        assertEquals("true", query.get("Timestamps"));
        assertEquals("false", query.get("directStreaming"));
        assertEquals("4294967295", query.get("seed"));
        assertEquals("true", query.get("auto_mode"));
    }

    @Test
    public void appliesSafeDefaults() {
        Map<String, String> query = ConnectionOptions.builder().build().toQueryMap();
        assertEquals("dedicated_concurrency", query.get("priority"));
        assertEquals("flash_v2_5", query.get("model_id"));
        assertEquals("pcm_16000", query.get("format"));
        assertEquals("20", query.get("idleTimeout"));
        assertFalse(query.containsKey("language_code"));
    }

    @Test
    public void translatesOutputFormatAliasAndLegacyMap() {
        Map<String, String> legacy = new LinkedHashMap<String, String>();
        legacy.put("output_format", "pcm_24000");
        legacy.put("model_id", "multilingual_v2");
        legacy.put("idleTimeout", "180");

        Map<String, String> query = ConnectionOptions.fromLegacyMap(legacy).toQueryMap();
        assertEquals("pcm_24000", query.get("format"));
        assertFalse(query.containsKey("output_format"));
        assertEquals("multilingual_v2", query.get("model_id"));
        assertEquals("180", query.get("idleTimeout"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnknownLegacyQueryParameter() {
        Map<String, String> legacy = new LinkedHashMap<String, String>();
        legacy.put("customer_hint", "unverified");
        ConnectionOptions.fromLegacyMap(legacy);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsIdleTimeoutAboveMaximum() {
        ConnectionOptions.builder().idleTimeout(181).build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNegativeSeed() {
        ConnectionOptions.builder().seed(-1L).build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsSeedAboveUnsignedIntMaximum() {
        ConnectionOptions.builder().seed(4294967296L).build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsOutputFormatConflict() {
        Map<String, String> legacy = new LinkedHashMap<String, String>();
        legacy.put("format", "pcm_8000");
        legacy.put("output_format", "pcm_16000");
        ConnectionOptions.fromLegacyMap(legacy);
    }
}

