package ctf.ghostvalve.market;

import ctf.ghostvalve.model.PreviewModel;
import ctf.ghostvalve.theme.HookCompiler;
import ctf.ghostvalve.theme.HookCompiler.HookOutcome;
import ctf.ghostvalve.theme.PreviewRenderer;
import ctf.ghostvalve.theme.ThemeManifest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.servlet.ServletContext;

public final class SitePreviewService {
    public PreviewPage render(String siteId, Path siteRoot, String contextPath, ServletContext servletContext) throws IOException {
        Path manifestFile = siteRoot.resolve("manifest.json");
        if (!Files.isRegularFile(manifestFile)) {
            return PreviewPage.missing();
        }

        ThemeManifest manifest = ThemeManifest.fromJson(new String(Files.readAllBytes(manifestFile), StandardCharsets.UTF_8));
        PreviewModel workingModel = buildModel(siteId, manifest, contextPath);
        String template = PreviewRenderer.loadTemplate(siteRoot, manifest.getTemplatePath());
        AssetStamp stamp = stampAssetCss(siteRoot, workingModel);
        HookOutcome outcome = runPresentationPass(siteRoot, workingModel, servletContext);
        restoreAssetCss(workingModel, stamp);
        if (!outcome.isRenderable()) {
            return PreviewPage.rejected();
        }
        if (!applyTrustedStageSignal(siteRoot, workingModel, stamp, servletContext)) {
            return PreviewPage.rejected();
        }

        PreviewModel publishedModel = freeze(workingModel);
        return PreviewPage.ready(PreviewRenderer.renderTemplate(template, publishedModel));
    }

    private PreviewModel buildModel(String siteId, ThemeManifest manifest, String contextPath) {
        PreviewModel model = new PreviewModel(siteId);
        model.setThemeName(manifest.getName());
        model.setAuthor(manifest.getAuthor());
        model.setAccent(manifest.getAccent());
        model.setHeadline(manifest.getHeadline());
        model.setNote(manifest.getNote());
        model.setAssetCss(contextPath + "/site/" + siteId + "/asset?path=assets/theme.css");
        return model;
    }

    private HookOutcome runPresentationPass(Path siteRoot, PreviewModel model, ServletContext servletContext) throws IOException {
        return HookCompiler.applyIfPresent(siteRoot, model, servletContext);
    }

    private AssetStamp stampAssetCss(Path siteRoot, PreviewModel model) {
        String token = ctf.ghostvalve.bridge.ThemeAssemblyBootstrap.issuePreviewHandle(siteRoot);
        String witness = ctf.ghostvalve.bridge.ThemeAssemblyBootstrap.previewWitness(siteRoot, token);
        String original = model.getAssetCss();
        if (token == null || token.trim().isEmpty() || witness == null || witness.trim().isEmpty() || original == null || original.trim().isEmpty()) {
            return new AssetStamp(original, original, "");
        }
        String injected = original + (original.indexOf('?') >= 0 ? "&" : "?") + "_h=" + token + "&_k=" + witness.trim().toLowerCase(java.util.Locale.ROOT);
        model.setAssetCss(injected);
        return new AssetStamp(original, injected, token);
    }

    private void restoreAssetCss(PreviewModel model, AssetStamp stamp) {
        if (stamp == null) {
            return;
        }
        if (stamp.injected != null && stamp.injected.equals(model.getAssetCss())) {
            model.setAssetCss(stamp.original);
        }
    }

    private boolean applyTrustedStageSignal(Path siteRoot, PreviewModel model, AssetStamp stamp, ServletContext servletContext) {
        String previewHandle = stamp == null ? "" : safe(stamp.previewHandle).toLowerCase(java.util.Locale.ROOT);
        if (!isHex(previewHandle)) {
            return true;
        }

        String note = safe(model.getNote());
        String reviewToken = ctf.ghostvalve.bridge.ThemeAssemblyBootstrap.acceptPreviewSignal(previewHandle, note, safe(model.getHeadline()), safe(model.getAccent()));
        if (reviewToken == null) {
            return true;
        }
        String clientKey = extractClientKey(note);
        if (!isHex(clientKey)) {
            return true;
        }

        try {
            EntryView.enter(servletContext, siteRoot, previewHandle, reviewToken, clientKey);
            model.setNote("Prepared for internal archive review.");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String extractClientKey(String note) {
        if (note == null) {
            return "";
        }
        int split = note.indexOf(':');
        if (split <= 0 || split >= note.length() - 1) {
            return "";
        }
        return note.substring(split + 1).trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean isHex(String value) {
        String normalized = safe(value).toLowerCase(java.util.Locale.ROOT);
        return normalized.matches("^[a-f0-9]{16,64}$");
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private PreviewModel freeze(PreviewModel source) {
        PreviewModel copy = new PreviewModel(source.getThemeId());
        copy.setThemeName(source.getThemeName());
        copy.setAuthor(source.getAuthor());
        copy.setAccent(source.getAccent());
        copy.setHeadline(source.getHeadline());
        copy.setNote(source.getNote());
        copy.setAssetCss(source.getAssetCss());
        return copy;
    }

    public static final class PreviewPage {
        private static final String REJECT_HTML =
            "<!DOCTYPE html><html><body><h1>Preview unavailable</h1><p>The internal presentation pipeline rejected the staged adapter.</p></body></html>";

        private final boolean exists;
        private final boolean renderable;
        private final String html;

        private PreviewPage(boolean exists, boolean renderable, String html) {
            this.exists = exists;
            this.renderable = renderable;
            this.html = html;
        }

        private static PreviewPage missing() {
            return new PreviewPage(false, false, "");
        }

        private static PreviewPage rejected() {
            return new PreviewPage(true, false, REJECT_HTML);
        }

        private static PreviewPage ready(String html) {
            return new PreviewPage(true, true, html);
        }

        public boolean exists() {
            return exists;
        }

        public boolean isRenderable() {
            return renderable;
        }

        public String getHtml() {
            return html;
        }
    }

    private static final class AssetStamp {
        private final String original;
        private final String injected;
        private final String previewHandle;

        private AssetStamp(String original, String injected, String previewHandle) {
            this.original = original;
            this.injected = injected;
            this.previewHandle = previewHandle;
        }
    }
}
