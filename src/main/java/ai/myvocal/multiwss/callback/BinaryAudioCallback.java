package ai.myvocal.multiwss.callback;

@FunctionalInterface
public interface BinaryAudioCallback {
    void onAudio(byte[] audio);
}

