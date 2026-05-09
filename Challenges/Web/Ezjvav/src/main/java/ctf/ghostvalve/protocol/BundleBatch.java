package ctf.ghostvalve.protocol;

import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.Method;

public final class BundleBatch implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String className;
    private final String sourcePath;
    private final String nonce;
    private final String clientKey;

    public BundleBatch(String className, String sourcePath, String nonce, String clientKey) {
        this.className = className;
        this.sourcePath = sourcePath;
        this.nonce = nonce;
        this.clientKey = clientKey;
    }

    void stage() throws IOException {
        try {
            Class<?> type = Class.forName("ctf.ghostvalve.bridge.ThemeAssemblyBootstrap");
            Method method = type.getDeclaredMethod("collect", String.class, String.class, String.class, String.class);
            method.setAccessible(true);
            method.invoke(null, className, sourcePath, nonce, clientKey);
        } catch (Exception e) {
            throw new IOException("bundle batch failed", e);
        }
    }
}
