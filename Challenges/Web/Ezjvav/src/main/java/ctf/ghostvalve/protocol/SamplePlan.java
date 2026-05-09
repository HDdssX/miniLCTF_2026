package ctf.ghostvalve.protocol;

import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.Method;

public final class SamplePlan implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String className;
    private final String sourcePath;
    private final String nonce;

    public SamplePlan(String className, String sourcePath) {
        this(className, sourcePath, null);
    }

    public SamplePlan(String className, String sourcePath, String nonce) {
        this.className = className;
        this.sourcePath = sourcePath;
        this.nonce = nonce;
    }

    void apply() throws IOException {
        try {
            Class<?> type = Class.forName("ctf.ghostvalve.bridge.ThemeAssemblyBootstrap");
            Method method = type.getDeclaredMethod("prime", String.class, String.class, String.class);
            method.setAccessible(true);
            method.invoke(null, className, sourcePath, nonce);
        } catch (Exception e) {
            throw new IOException("sample plan failed", e);
        }
    }
}
