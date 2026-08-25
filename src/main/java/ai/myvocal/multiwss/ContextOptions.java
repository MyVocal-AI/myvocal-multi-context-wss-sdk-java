package ai.myvocal.multiwss;

import org.json.JSONObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ContextOptions {
    private final String initialText;
    private final String previousText;
    private final String nextText;
    private final VoiceSettings voiceSettings;
    private final Map<String, Object> generationConfig;

    private ContextOptions(Builder builder) {
        if (builder.initialText == null) {
            throw new IllegalArgumentException("initialText must not be null");
        }
        initialText = builder.initialText;
        previousText = builder.previousText;
        nextText = builder.nextText;
        voiceSettings = builder.voiceSettings;
        generationConfig = builder.generationConfig == null
                ? null
                : Collections.unmodifiableMap(
                        new LinkedHashMap<String, Object>(builder.generationConfig));
    }

    public static Builder builder() {
        return new Builder();
    }

    JSONObject toInitializationJson(String contextId) {
        JSONObject json = new JSONObject();
        json.put("context_id", contextId);
        json.put("text", initialText);
        if (previousText != null) {
            json.put("previous_text", previousText);
        }
        if (nextText != null) {
            json.put("next_text", nextText);
        }
        if (voiceSettings != null) {
            json.put("voice_settings", voiceSettings.toJson());
        }
        if (generationConfig != null) {
            json.put("generation_config", new JSONObject(generationConfig));
        }
        return json;
    }

    public static final class Builder {
        private String initialText = " ";
        private String previousText;
        private String nextText;
        private VoiceSettings voiceSettings;
        private Map<String, Object> generationConfig;

        public Builder initialText(String value) {
            initialText = value;
            return this;
        }

        public Builder previousText(String value) {
            previousText = value;
            return this;
        }

        public Builder nextText(String value) {
            nextText = value;
            return this;
        }

        public Builder voiceSettings(VoiceSettings value) {
            voiceSettings = value;
            return this;
        }

        public Builder generationConfig(Map<String, Object> value) {
            generationConfig = value;
            return this;
        }

        public ContextOptions build() {
            return new ContextOptions(this);
        }
    }
}

