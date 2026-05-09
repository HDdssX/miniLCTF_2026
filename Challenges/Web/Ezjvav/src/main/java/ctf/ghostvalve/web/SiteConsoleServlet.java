package ctf.ghostvalve.web;

import ctf.ghostvalve.market.SiteDeliveryService;
import ctf.ghostvalve.market.SiteDeliveryService.AssetPayload;
import ctf.ghostvalve.market.SiteDeliveryService.ExportPackage;
import ctf.ghostvalve.market.SitePreviewService;
import ctf.ghostvalve.market.SitePreviewService.PreviewPage;
import ctf.ghostvalve.theme.ThemePaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.zip.ZipOutputStream;

@WebServlet("/site/*")
public class SiteConsoleServlet extends HttpServlet {
    private static final String PUBLISH_COOKIE = "theme_publish";
    private final SitePreviewService previewService = new SitePreviewService();
    private final SiteDeliveryService deliveryService = new SiteDeliveryService();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {
        String pathInfo = req.getPathInfo();
        if (pathInfo == null || "/".equals(pathInfo)) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        String[] parts = pathInfo.split("/");
        if (parts.length < 3) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        String siteId;
        try {
            siteId = ThemePaths.normalizeThemeId(parts[1]);
        } catch (IllegalArgumentException e) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        Path siteRoot = ThemePaths.themeRoot(siteId);
        if (!Files.exists(siteRoot)) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        String action = parts[2];
        if ("preview".equals(action)) {
            renderPreview(siteId, siteRoot, req, resp);
            return;
        }
        if ("export".equals(action)) {
            exportSite(siteId, siteRoot, req, resp);
            return;
        }
        if ("asset".equals(action)) {
            serveAsset(siteRoot, req, resp);
            return;
        }

        resp.sendError(HttpServletResponse.SC_NOT_FOUND);
    }

    private void renderPreview(String siteId, Path siteRoot, HttpServletRequest req, HttpServletResponse resp) throws IOException {
        PreviewPage page = previewService.render(siteId, siteRoot, req.getContextPath(), getServletContext());
        if (!page.exists()) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        if (!page.isRenderable()) {
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            resp.setContentType("text/html;charset=UTF-8");
            resp.getWriter().write(page.getHtml());
            return;
        }

        resp.setContentType("text/html;charset=UTF-8");
        resp.setHeader("Cache-Control", "no-store");
        resp.setHeader("X-Site-Preview", "ready");
        resp.getWriter().write(page.getHtml());
    }

    private void exportSite(String siteId, Path siteRoot, HttpServletRequest req, HttpServletResponse resp) throws IOException {
        ExportPackage exportPackage = deliveryService.prepareExport(siteId, siteRoot);
        String receipt = ctf.ghostvalve.bridge.ThemeAssemblyBootstrap.consumePendingReceipt(siteRoot, readCookie(req, PUBLISH_COOKIE), exportPackage.getIndex());
        resp.setContentType("application/zip");
        resp.setHeader("Cache-Control", "no-store");
        resp.setHeader("Content-Disposition", "attachment; filename=\"" + exportPackage.getFileName() + "\"");
        resp.setHeader("X-Site-Index", exportPackage.getIndex());
        if (receipt != null && !receipt.trim().isEmpty()) {
            resp.setHeader("X-Publication-Receipt", receipt.trim());
        }

        try (ServletOutputStream output = resp.getOutputStream(); ZipOutputStream zip = new ZipOutputStream(output, java.nio.charset.StandardCharsets.UTF_8)) {
            exportPackage.writeTo(zip);
            zip.finish();
        }
    }

    private void serveAsset(Path siteRoot, HttpServletRequest req, HttpServletResponse resp) throws IOException {
        AssetPayload asset = deliveryService.openAsset(siteRoot, req.getParameter("path"), getServletContext());
        if (asset == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        resp.setContentType(asset.getMimeType());
        resp.getOutputStream().write(asset.getBytes());
    }

    private static String readCookie(HttpServletRequest req, String name) {
        javax.servlet.http.Cookie[] cookies = req.getCookies();
        if (cookies == null || name == null) {
            return null;
        }
        for (javax.servlet.http.Cookie cookie : cookies) {
            if (cookie != null && name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
