package ctf.ghostvalve.protocol;

import ctf.ghostvalve.model.PreviewModel;
import java.io.Serializable;

public final class PreviewPatch implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String headline;
    private final String note;
    private final String accent;

    public PreviewPatch(String headline, String note, String accent) {
        this.headline = headline;
        this.note = note;
        this.accent = accent;
    }

    public static PreviewPatch empty() {
        return new PreviewPatch(null, null, null);
    }

    public void applyTo(PreviewModel model) {
        if (model == null) {
            return;
        }
        if (headline != null) {
            model.setHeadline(headline);
        }
        if (note != null) {
            model.setNote(note);
        }
        if (accent != null) {
            model.setAccent(accent);
        }
    }
}
