package ctf.ghostvalve.model;

import java.io.Serializable;

public class PreviewModel implements Serializable {
    private static final long serialVersionUID = 1L;
    private final String themeId;
    private String themeName;
    private String author;
    private String accent;
    private String headline;
    private String note;
    private String assetCss;

    public PreviewModel(String themeId) {
        this.themeId = themeId;
    }

    public String getThemeId() {
        return themeId;
    }

    public String getThemeName() {
        return themeName;
    }

    public void setThemeName(String themeName) {
        this.themeName = themeName;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public String getAccent() {
        return accent;
    }

    public void setAccent(String accent) {
        this.accent = accent;
    }

    public String getHeadline() {
        return headline;
    }

    public void setHeadline(String headline) {
        this.headline = headline;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public String getAssetCss() {
        return assetCss;
    }

    public void setAssetCss(String assetCss) {
        this.assetCss = assetCss;
    }
}
