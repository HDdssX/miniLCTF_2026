package ctf.ghostvalve.web;

import ctf.ghostvalve.bridge.ThemeAssemblyBootstrap;
import ctf.ghostvalve.theme.JsonUtil;
import java.io.IOException;
import java.util.Locale;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@WebServlet(urlPatterns = {
    "/ctf/flag",
    "/ctf/flag/*",
    "/api/ctf/flag",
    "/api/ctf/flag/*",
    "/flag"
})
public class FlagServlet extends HttpServlet {
    private static final String COOKIE_NAME = "ctf_flag_probe";
    private static final String CLIENT_HEADER = "X-CTF-Client";
    private static final String PROOF_HEADER = "X-CTF-Proof";
    private static final String MARKER_HEADER = "X-CTF-Marker";

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {
        String path = normalizePath(req);
        if ("/open".equals(path) || "/stage".equals(path)) {
            openProbe(req, resp);
            return;
        }
        if ("/status".equals(path)) {
            status(req, resp);
            return;
        }
        index(resp);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {
        String path = normalizePath(req);
        if ("/commit".equals(path) || "/seal".equals(path)) {
            commit(req, resp);
            return;
        }
        index(resp);
    }

    private void index(HttpServletResponse resp) throws IOException {
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType("application/json;charset=UTF-8");
        resp.setHeader("Cache-Control", "no-store");
        resp.getWriter().write("{"
            + "\"route\":\"ctf/flag\","
            + "\"state\":\"supplemental-audit\","
            + "\"next\":[\"/ctf/flag/open\",\"/ctf/flag/commit\",\"/ctf/flag/status\"],"
            + "\"note\":\"supplemental archive review is available for staged deliveries\""
            + "}");
    }

    private void openProbe(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String client = normalizeClient(req.getHeader(CLIENT_HEADER));
        if (client == null) {
            client = "anon";
        }
        ThemeAssemblyBootstrap.ChallengeBundle bundle = ThemeAssemblyBootstrap.openDecoyPass(client);
        if (bundle == null) {
            respond(resp, HttpServletResponse.SC_CONFLICT, "idle", "supplemental review is unavailable");
            return;
        }
        Cookie cookie = new Cookie(COOKIE_NAME, bundle.getCookieValue());
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        resp.addCookie(cookie);
        resp.setHeader(MARKER_HEADER, bundle.getMarker());
        resp.setHeader("Cache-Control", "no-store");
        resp.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }

    private void commit(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String cookieValue = readCookie(req, COOKIE_NAME);
        String client = normalizeClient(req.getHeader(CLIENT_HEADER));
        String proof = clean(req.getHeader(PROOF_HEADER));
        String marker = clean(req.getHeader(MARKER_HEADER));
        boolean accepted;
        try {
            accepted = ThemeAssemblyBootstrap.completePass(client, cookieValue, proof, marker);
        } catch (Exception e) {
            throw new IOException("supplemental review failed", e);
        }
        if (!accepted) {
            respond(resp, HttpServletResponse.SC_CONFLICT, "review-held", "supplemental archive checkpoint did not pass");
            return;
        }
        resp.setStatus(HttpServletResponse.SC_ACCEPTED);
        resp.setHeader("Cache-Control", "no-store");
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write("{"
            + "\"state\":\"queued\","
            + "\"sink\":\"supplemental-review\","
            + "\"result\":\"archive checkpoint accepted\""
            + "}");
    }

    private void status(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String state = ThemeAssemblyBootstrap.decoyStatus(readCookie(req, COOKIE_NAME));
        if ("pending".equals(state)) {
            respond(resp, HttpServletResponse.SC_OK, "pending", "supplemental review is still waiting");
            return;
        }
        if ("archived".equals(state)) {
            respond(resp, HttpServletResponse.SC_OK, "archived", "supplemental review has already been recorded");
            return;
        }
        respond(resp, HttpServletResponse.SC_OK, "idle", "no supplemental review is active");
    }

    private static String normalizePath(HttpServletRequest req) {
        String path = req.getPathInfo();
        if (path == null || path.trim().isEmpty()) {
            String uri = req.getRequestURI();
            if (uri.endsWith("/open") || uri.endsWith("/stage") || uri.endsWith("/commit") || uri.endsWith("/seal") || uri.endsWith("/status")) {
                return uri.substring(uri.lastIndexOf('/'));
            }
            return "/";
        }
        return path;
    }

    private static void respond(HttpServletResponse resp, int code, String state, String message) throws IOException {
        resp.setStatus(code);
        resp.setHeader("Cache-Control", "no-store");
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write("{\"state\":\"" + JsonUtil.escape(state) + "\",\"message\":\"" + JsonUtil.escape(message) + "\"}");
    }

    private static String normalizeClient(String value) {
        if (value == null) {
            return null;
        }
        String client = value.trim().toLowerCase(Locale.ROOT);
        return client.matches("^[a-z0-9_.:-]{4,80}$") ? client : null;
    }

    private static String clean(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String readCookie(HttpServletRequest req, String name) {
        Cookie[] cookies = req.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (cookie != null && name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
