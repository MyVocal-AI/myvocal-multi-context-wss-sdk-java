package ai.myvocal.multiwss;

import org.json.JSONObject;

public final class VoiceSettings {
    private final Double stability;
    private final Double similarityBoost;
    private final Double style;
    private final Boolean useSpeakerBoost;

    private VoiceSettings(Builder builder) {
        validateRange("stability", builder.stability);
        validateRange("similarity_boost", builder.similarityBoost);
        validateRange("style", builder.style);
        stability = builder.stability;
        similarityBoost = builder.similarityBoost;
        style = builder.style;
        useSpeakerBoost = builder.useSpeakerBoost;
    }

    public static Builder builder() {
        return new Builder();
    }

    JSONObject toJson() {
        JSONObject json = new JSONObject();
        if (stability != null) {
            json.put("stability", stability);
        }
        if (similarityBoost != null) {
            json.put("similarity_boost", similarityBoost);
        }
        if (style != null) {
            json.put("style", style);
        }
        if (useSpeakerBoost != null) {
            json.put("use_speaker_boost", useSpeakerBoost);
        }
        return json;
    }

    private static void validateRange(String name, Double value) {
        if (value != null && (value < 0.0 || value > 1.0)) {
            throw new IllegalArgumentException(name + " must be between 0 and 1");
        }
    }

    public static final class Builder {
        private Double stability;
        private Double similarityBoost;
        private Double style;
        private Boolean useSpeakerBoost;

        public Builder stability(double value) {
            stability = value;
            return this;
        }

        public Builder similarityBoost(double value) {
            similarityBoost = value;
            return this;
        }

        public Builder style(double value) {
            style = value;
            return this;
        }

        public Builder useSpeakerBoost(boolean value) {
            useSpeakerBoost = value;
            return this;
        }

        public VoiceSettings build() {
            return new VoiceSettings(this);
        }
    }
}

