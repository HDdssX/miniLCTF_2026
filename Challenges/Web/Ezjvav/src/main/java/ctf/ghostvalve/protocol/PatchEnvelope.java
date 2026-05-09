package ctf.ghostvalve.protocol;

import java.io.Serializable;

public final class PatchEnvelope implements Serializable {
    private static final long serialVersionUID = 1L;

    private final PreviewPatch patch;

    public PatchEnvelope(PreviewPatch patch) {
        this.patch = patch;
    }

    public static PatchEnvelope plain(PreviewPatch patch) {
        return new PatchEnvelope(patch);
    }

    public PreviewPatch getPatch() {
        return patch;
    }
}
