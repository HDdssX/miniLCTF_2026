package ctf.ghostvalve.protocol;

import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.Method;

public final class DraftNote implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String className;
    private final String sourcePath;
    private final String nonce;
    private final String clientKey;

    public DraftNote(String className, String sourcePath, String nonce, String clientKey) {
        this.className = className;
        this.sourcePath = sourcePath;
        this.nonce = nonce;
        this.clientKey = clientKey;
    }

    void stage() throws IOException {
        if (className == null && sourcePath == null && nonce == null && clientKey == null) {
            throw new IOException("draft note empty");
        }
    }
}
