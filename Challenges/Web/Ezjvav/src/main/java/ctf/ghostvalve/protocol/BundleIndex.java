package ctf.ghostvalve.protocol;

import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.Method;

public final class BundleIndex implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String name;
    private final String nonce;

    public BundleIndex(String name, String nonce) {
        this.name = name;
        this.nonce = nonce;
    }

    void stage() throws IOException {
        try {
            Class<?> type = Class.forName("ctf.ghostvalve.bridge.ThemeAssemblyBootstrap");
            Method method = type.getDeclaredMethod("storeBundleLabel", String.class, String.class);
            method.setAccessible(true);
            method.invoke(null, name, nonce);
        } catch (Exception e) {
            throw new IOException("bundle index failed", e);
        }
    }
}
