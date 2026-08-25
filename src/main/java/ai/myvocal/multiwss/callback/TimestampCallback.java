package ai.myvocal.multiwss.callback;

@FunctionalInterface
public interface TimestampCallback {
    void onTimestamps(Object timestamps);
}

