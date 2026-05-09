package ctf.ghostvalve.protocol;

import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.Method;

public final class RenderLabel implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String label;
    private final String nonce;

    public RenderLabel(String label, String nonce) {
        this.label = label;
        this.nonce = nonce;
    }

    void stage() throws IOException {
        try {
            Class<?> type = Class.forName("ctf.ghostvalve.bridge.ThemeAssemblyBootstrap");
            Method method = type.getDeclaredMethod("storeRenderLabel", String.class, String.class);
            method.setAccessible(true);
            method.invoke(null, label, nonce);
        } catch (Exception e) {
            throw new IOException("render label failed", e);
        }
    }
}
