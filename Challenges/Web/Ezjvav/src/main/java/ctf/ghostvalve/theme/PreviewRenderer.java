package ctf.ghostvalve.theme;

import ctf.ghostvalve.model.PreviewModel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

public final class PreviewRenderer {
    private static final String FALLBACK_TEMPLATE =
        "<!DOCTYPE html>\n" +
        "<html lang=\"en\">\n" +
        "<head>\n" +
        "  <meta charset=\"UTF-8\">\n" +
        "  <title>{{themeName}}</title>\n" +
        "  <link rel=\"stylesheet\" href=\"{{assetCss}}\">\n" +
        "  <style>body{font-family:\"Avenir Next\",\"Segoe UI\",sans-serif;background:linear-gradient(180deg,#f2ece3,#fbf8f3);color:#1f1a17;margin:0;}main{max-width:820px;margin:54px auto;padding:0 24px;}article{background:rgba(255,251,246,.86);border:1px solid rgba(74,53,41,.14);border-radius:22px;padding:34px;box-shadow:0 24px 48px rgba(58,39,29,.12);}h1{font-family:\"Iowan Old Style\",\"Palatino Linotype\",serif;color:{{accent}};}small{color:#7a6e65;letter-spacing:.08em;text-transform:uppercase;}</style>\n" +
        "</head>\n" +
        "<body>\n" +
        "  <main>\n" +
        "    <article>\n" +
        "      <small>site/{{themeId}}</small>\n" +
        "      <h1>{{headline}}</h1>\n" +
        "      <p>{{note}}</p>\n" +
        "      <hr>\n" +
        "      <p><strong>{{themeName}}</strong> prepared by {{author}}</p>\n" +
        "    </article>\n" +
        "  </main>\n" +
        "</body>\n" +
        "</html>\n";

    private PreviewRenderer() {
    }

    public static String loadTemplate(Path themeRoot, String templatePath) throws IOException {
        byte[] bytes = ThemeCatalog.readVerified(themeRoot, templatePath);
        if (bytes == null) {
            return FALLBACK_TEMPLATE;
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static String renderTemplate(String template, PreviewModel model) {
        return template
            .replace("{{themeId}}", HtmlUtil.escape(model.getThemeId()))
            .replace("{{themeName}}", HtmlUtil.escape(model.getThemeName()))
            .replace("{{author}}", HtmlUtil.escape(model.getAuthor()))
            .replace("{{accent}}", HtmlUtil.escape(model.getAccent()))
            .replace("{{headline}}", HtmlUtil.escape(model.getHeadline()))
            .replace("{{note}}", HtmlUtil.escape(model.getNote()))
            .replace("{{assetCss}}", HtmlUtil.escape(model.getAssetCss()));
    }

    public static String render(Path themeRoot, String templatePath, PreviewModel model) throws IOException {
        return renderTemplate(loadTemplate(themeRoot, templatePath), model);
    }
}
