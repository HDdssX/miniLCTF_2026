package ctf.ghostvalve.protocol;

import java.io.IOException;
import java.io.Serializable;

public final class WarmupProfile implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String token;

    public WarmupProfile(String token) {
        this.token = token;
    }

    void warm() throws IOException {
        if (token == null) {
            throw new IOException("preview warmup unavailable");
        }
    }
}
