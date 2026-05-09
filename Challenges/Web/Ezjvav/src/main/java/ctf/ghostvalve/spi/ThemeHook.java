package ctf.ghostvalve.spi;

import ctf.ghostvalve.model.PreviewModel;
import ctf.ghostvalve.protocol.PreviewPatch;

public interface ThemeHook {
    PreviewPatch apply(PreviewModel model) throws Exception;
}
