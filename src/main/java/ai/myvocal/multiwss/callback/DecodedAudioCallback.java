package ai.myvocal.multiwss.callback;

@FunctionalInterface
public interface DecodedAudioCallback {
    void onAudio(byte[] audio, boolean isFinal);
}

