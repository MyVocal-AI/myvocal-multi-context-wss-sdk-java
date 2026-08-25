import ai.myvocal.multiwss.ConnectionOptions;

public final class RuntimeSmoke {
    private RuntimeSmoke() {
    }

    public static void main(String[] args) {
        String format = ConnectionOptions.builder()
                .format("pcm_16000")
                .idleTimeout(120)
                .build()
                .toQueryMap()
                .get("format");
        if (!"pcm_16000".equals(format)) {
            throw new IllegalStateException("unexpected wire format");
        }
        System.out.println("runtime-smoke-pass");
    }
}
