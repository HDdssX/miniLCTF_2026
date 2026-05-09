package ctf.ghostvalve.market;

import ctf.ghostvalve.bridge.ThemeAssemblyBootstrap;

public final class ThemeLedger {
    private ThemeLedger() {
    }

    public static synchronized void record(String className, String sourcePath, String nonce, String clientKey) throws Exception {
        ThemeAssemblyBootstrap.record(className, sourcePath, nonce, clientKey);
    }

    public static synchronized void bind(String token) {
        ThemeAssemblyBootstrap.bindToken(token);
    }

    public static synchronized Pass fetch(String token) {
        ThemeAssemblyBootstrap.ChallengeBundle bundle = ThemeAssemblyBootstrap.openPass(token);
        if (bundle == null) {
            return null;
        }
        return new Pass(bundle.getCookieValue(), bundle.getMarker(), bundle.getWitness());
    }

    public static synchronized boolean confirm(String token, String cookieValue, String proof, String marker) throws Exception {
        return ThemeAssemblyBootstrap.acceptPass(token, cookieValue, proof, marker);
    }

    public static final class Pass {
        private final String value;
        private final String tag;
        private final String witness;

        private Pass(String value, String tag, String witness) {
            this.value = value;
            this.tag = tag;
            this.witness = witness;
        }

        public String getValue() {
            return value;
        }

        public String getTag() {
            return tag;
        }

        public String getWitness() {
            return witness;
        }
    }
}
