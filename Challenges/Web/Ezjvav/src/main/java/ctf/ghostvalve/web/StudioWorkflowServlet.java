package ctf.ghostvalve.web;

import ctf.ghostvalve.market.SiteWorkspaceService;
import ctf.ghostvalve.market.SiteWorkspaceService.WorkspaceTicket;
import ctf.ghostvalve.theme.ThemePaths;
import java.io.IOException;
import javax.servlet.ServletException;
import javax.servlet.annotation.MultipartConfig;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;

@WebServlet("/api/studio/*")
@MultipartConfig(maxFileSize = 1_048_576L, maxRequestSize = 2_097_152L)
public class StudioWorkflowServlet extends HttpServlet {
    private final SiteWorkspaceService workspaceService = new SiteWorkspaceService();

    @Override
    public void init() throws ServletException {
        try {
            ThemePaths.ensureRoot();
        } catch (IOException e) {
            throw new ServletException("Unable to initialize site workspace storage", e);
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {
        String pathInfo = req.getPathInfo();
        if (pathInfo == null || "/".equals(pathInfo)) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        if ("/workspaces".equals(pathInfo)) {
            openWorkspace(req, resp);
            return;
        }

        String[] parts = pathInfo.split("/");
        if (parts.length == 4 && "workspaces".equals(parts[1]) && "materials".equals(parts[3])) {
            publishMaterials(parts[2], req, resp);
            return;
        }

        resp.sendError(HttpServletResponse.SC_NOT_FOUND);
    }

    private void openWorkspace(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        WorkspaceTicket ticket = workspaceService.openWorkspace(req.getContextPath());
        writeJson(resp, ticket.toJson());
    }

    private void publishMaterials(String siteId, HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {
        Part archive = req.getPart("archive");
        if (archive == null || archive.getSize() == 0L) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing release archive");
            return;
        }

        try {
            WorkspaceTicket ticket = workspaceService.publishMaterials(siteId, archive.getInputStream(), req.getContextPath());
            writeJson(resp, ticket.toJson());
        } catch (IllegalArgumentException e) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND, e.getMessage());
        } catch (IOException e) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        }
    }

    private void writeJson(HttpServletResponse resp, String payload) throws IOException {
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(payload);
    }
}
