package ctf.ghostvalve.theme;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ThemeManifest {
    private static final Pattern HEX_COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");

    private final String name;
    private final String author;
    private final String accent;
    private final String headline;
    private final String note;
    private final String templatePath;
    private final String sampleClassName;
    private final String sampleSourcePath;

    private ThemeManifest(String name, String author, String accent, String headline, String note, String templatePath, String sampleClassName, String sampleSourcePath) {
        this.name = name;
        this.author = author;
        this.accent = accent;
        this.headline = headline;
        this.note = note;
        this.templatePath = templatePath;
        this.sampleClassName = sampleClassName;
        this.sampleSourcePath = sampleSourcePath;
    }

    public static ThemeManifest fromJson(String json) {
        String name = extractString(json, "name", "Untitled Public Site");
        String author = extractString(json, "author", "communications-office");
        String accent = sanitizeAccent(extractString(json, "accent", "#38bdf8"));
        String headline = extractString(json, "headline", "Northbridge preview draft");
        String note = extractString(json, "note", "Prepared for internal review inside the public site studio.");
        String templatePath = sanitizeTemplatePath(extractString(json, "template", "templates/preview.html"));
        String sampleClassName = extractString(json, "sampleClassName", "");
        String sampleSourcePath = extractString(json, "sampleSourcePath", "");
        return new ThemeManifest(name, author, accent, headline, note, templatePath, sampleClassName, sampleSourcePath);
    }

    private static String extractString(String json, String key, String fallback) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) {
            return fallback;
        }
        return unescape(matcher.group(1));
    }

    private static String sanitizeAccent(String value) {
        return HEX_COLOR.matcher(value).matches() ? value : "#38bdf8";
    }

    private static String sanitizeTemplatePath(String value) {
        if (value.startsWith("templates/") && !value.contains("..") && !value.contains("\\")) {
            return value;
        }
        return "templates/preview.html";
    }

    private static String unescape(String value) {
        return value
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t");
    }

    public String getName() {
        return name;
    }

    public String getAuthor() {
        return author;
    }

    public String getAccent() {
        return accent;
    }

    public String getHeadline() {
        return headline;
    }

    public String getNote() {
        return note;
    }

    public String getTemplatePath() {
        return templatePath;
    }

    public String getSampleClassName() {
        return sampleClassName;
    }

    public String getSampleSourcePath() {
        return sampleSourcePath;
    }
}
