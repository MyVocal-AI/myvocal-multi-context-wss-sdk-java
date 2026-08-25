package ai.myvocal.multiwss;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable connection-level options for the multi-context WebSocket endpoint.
 */
public final class ConnectionOptions {
    public static final long MAX_SEED = 4294967295L;

    private final Map<String, String> query;

    private ConnectionOptions(Builder builder) {
        validate(builder);
        LinkedHashMap<String, String> values = new LinkedHashMap<String, String>();
        values.put("priority", builder.priority);
        values.put("model_id", builder.modelId);
        values.put("format", builder.format);
        if (builder.languageCode != null) {
            values.put("language_code", builder.languageCode);
        }
        values.put("idleTimeout", String.valueOf(builder.idleTimeout));
        if (builder.timestamps != null) {
            values.put("Timestamps", String.valueOf(builder.timestamps));
        }
        if (builder.directStreaming != null) {
            values.put("directStreaming", String.valueOf(builder.directStreaming));
        }
        if (builder.seed != null) {
            values.put("seed", String.valueOf(builder.seed));
        }
        if (builder.autoMode != null) {
            values.put("auto_mode", String.valueOf(builder.autoMode));
        }
        query = Collections.unmodifiableMap(values);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Adapts the original map-based API. output_format is accepted as an alias
     * and sent to MyVocal as format.
     */
    public static ConnectionOptions fromLegacyMap(Map<String, String> params) {
        Builder builder = builder();
        if (params == null) {
            return builder.build();
        }

        String format = params.get("format");
        String outputFormat = params.get("output_format");
        if (format != null && outputFormat != null && !format.equals(outputFormat)) {
            throw new IllegalArgumentException("format conflicts with output_format");
        }

        for (Map.Entry<String, String> entry : params.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if ("priority".equals(key)) {
                builder.priority(value);
            } else if ("model_id".equals(key)) {
                builder.modelId(value);
            } else if ("format".equals(key) || "output_format".equals(key)) {
                builder.format(value);
            } else if ("language_code".equals(key)) {
                builder.languageCode(value);
            } else if ("idleTimeout".equals(key)) {
                builder.idleTimeout(parseInteger(key, value));
            } else if ("Timestamps".equals(key)) {
                builder.timestamps(parseBoolean(key, value));
            } else if ("directStreaming".equals(key)) {
                builder.directStreaming(parseBoolean(key, value));
            } else if ("seed".equals(key)) {
                builder.seed(parseLong(key, value));
            } else if ("auto_mode".equals(key)) {
                builder.autoMode(parseBoolean(key, value));
            } else {
                builder.extension(key, value);
            }
        }
        return builder.build();
    }

    public Map<String, String> toQueryMap() {
        return query;
    }

    private static int parseInteger(String name, String value) {
        try {
            return Integer.parseInt(value);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException(name + " must be an integer", error);
        }
    }

    private static long parseLong(String name, String value) {
        try {
            return Long.parseLong(value);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException(name + " must be an integer", error);
        }
    }

    private static boolean parseBoolean(String name, String value) {
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw new IllegalArgumentException(name + " must be true or false");
        }
        return Boolean.parseBoolean(value);
    }

    private static void validate(Builder builder) {
        if (!"dedicated_concurrency".equals(builder.priority)) {
            throw new IllegalArgumentException(
                    "priority must be dedicated_concurrency");
        }
        if (!"flash_v2_5".equals(builder.modelId)
                && !"multilingual_v2".equals(builder.modelId)) {
            throw new IllegalArgumentException("unsupported model_id");
        }
        if (!isSupportedFormat(builder.format)) {
            throw new IllegalArgumentException("unsupported format");
        }
        if (builder.languageCode != null
                && !builder.languageCode.matches("[a-z]{2,3}")) {
            throw new IllegalArgumentException(
                    "language_code must be 2-3 lowercase ASCII letters");
        }
        if (builder.idleTimeout < 1 || builder.idleTimeout > 180) {
            throw new IllegalArgumentException("idleTimeout must be between 1 and 180 seconds");
        }
        if (builder.seed != null && (builder.seed < 0 || builder.seed > MAX_SEED)) {
            throw new IllegalArgumentException("seed must be between 0 and " + MAX_SEED);
        }
    }

    private static boolean isSupportedFormat(String value) {
        return "mp3_44100_64".equals(value)
                || "mp3_44100_96".equals(value)
                || "mp3_44100_128".equals(value)
                || "mp3_44100_192".equals(value)
                || "pcm_8000".equals(value)
                || "pcm_16000".equals(value)
                || "pcm_22050".equals(value)
                || "pcm_24000".equals(value)
                || "pcm_44100".equals(value)
                || "ulaw_8000".equals(value);
    }

    private static void requireText(String name, String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    public static final class Builder {
        private String priority = "dedicated_concurrency";
        private String modelId = "flash_v2_5";
        private String format = "pcm_16000";
        private String languageCode;
        private int idleTimeout = 20;
        private Boolean timestamps;
        private Boolean directStreaming;
        private Long seed;
        private Boolean autoMode;

        public Builder priority(String value) {
            priority = value;
            return this;
        }

        public Builder modelId(String value) {
            modelId = value;
            return this;
        }

        public Builder format(String value) {
            format = value;
            return this;
        }

        public Builder outputFormat(String value) {
            return format(value);
        }

        public Builder languageCode(String value) {
            languageCode = value;
            return this;
        }

        public Builder idleTimeout(int value) {
            idleTimeout = value;
            return this;
        }

        public Builder timestamps(boolean value) {
            timestamps = value;
            return this;
        }

        public Builder directStreaming(boolean value) {
            directStreaming = value;
            return this;
        }

        public Builder seed(long value) {
            seed = value;
            return this;
        }

        public Builder autoMode(boolean value) {
            autoMode = value;
            return this;
        }

        public Builder extension(String name, String value) {
            throw new IllegalArgumentException(
                    "unverified connection parameters are not supported");
        }

        public ConnectionOptions build() {
            return new ConnectionOptions(this);
        }
    }
}

