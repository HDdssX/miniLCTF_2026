package ctf.ghostvalve.web;

import ctf.ghostvalve.theme.JsonUtil;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@WebServlet({"/api/catalog/*", "/catalog/*"})
public class CatalogServlet extends HttpServlet {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ConcurrentMap<String, Session> SESSIONS = new ConcurrentHashMap<String, Session>();
    private static final long SESSION_TTL_MS = 7000L;
    private static final String COOKIE_NAME = "catalog_session";
    private static final String CLIENT_HEADER = "X-Catalog-Client";
    private static final String PROOF_HEADER = "X-Catalog-Proof";
    private static final String MARKER_HEADER = "X-Catalog-Marker";

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        route(req, resp);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        route(req, resp);
    }

    private void route(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String path = normalizePath(req);
        if ("/open".equals(path)) {
            open(req, resp);
            return;
        }
        if ("/export".equals(path)) {
            export(req, resp);
            return;
        }
        if ("/commit".equals(path)) {
            commit(req, resp);
            return;
        }
        if ("/status".equals(path) || "/".equals(path)) {
            status(req, resp);
            return;
        }
        respond(resp, 404, "missing", "catalog route unavailable");
    }

    private void open(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String client = normalizeClient(req.getHeader(CLIENT_HEADER));
        if (client == null) {
            respond(resp, 400, "client", "catalog client missing");
            return;
        }
        String receipt = randomHex(12);
        String marker = sha256Hex("catalog:" + client + ":" + receipt).substring(0, 16);
        String ticket = sha256Hex(client + ":" + receipt + ":" + marker).substring(0, 24);
        SESSIONS.put(receipt, new Session(client, marker, ticket, System.currentTimeMillis()));
        resp.addHeader("Set-Cookie", COOKIE_NAME + "=" + receipt + "; Path=/");
        resp.addHeader(MARKER_HEADER, marker);
        respond(resp, 202, "prepared", ticket);
    }

    private void export(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Session session = session(req);
        if (session == null) {
            respond(resp, 409, "expired", "catalog receipt expired");
            return;
        }
        String client = normalizeClient(req.getHeader(CLIENT_HEADER));
        if (!session.client.equals(client)) {
            respond(resp, 409, "client", "catalog client mismatch");
            return;
        }
        session.digest = sha256Hex(session.client + ":" + session.ticket + ":" + session.marker).substring(4, 36);
        session.exportedAt = System.currentTimeMillis();
        resp.addHeader("X-Catalog-Digest", session.digest);
        respond(resp, 202, "exported", session.digest);
    }

    private void commit(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Session session = session(req);
        if (session == null || session.digest == null || session.exportedAt <= 0L) {
            respond(resp, 409, "pending", "catalog export required");
            return;
        }
        String marker = clean(req.getHeader(MARKER_HEADER));
        String proof = clean(req.getHeader(PROOF_HEADER));
        String expected = sha256Hex(session.client + ":" + session.ticket + ":" + session.digest + ":" + session.marker).substring(8, 40);
        if (!session.marker.equals(marker) || !expected.equals(proof)) {
            respond(resp, 409, "proof", "catalog proof mismatch");
            return;
        }
        session.committed = true;
        respond(resp, 202, "committed", sha256Hex("bundle:" + session.digest).substring(0, 20));
    }

    private void status(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Session session = session(req);
        if (session == null) {
            respond(resp, 200, "idle", "catalog idle");
            return;
        }
        respond(resp, 200, session.committed ? "committed" : (session.digest == null ? "prepared" : "exported"), session.marker);
    }

    private static Session session(HttpServletRequest req) {
        String receipt = readCookie(req, COOKIE_NAME);
        if (receipt == null) {
            return null;
        }
        Session session = SESSIONS.get(receipt);
        if (session == null || System.currentTimeMillis() - session.createdAt > SESSION_TTL_MS) {
            SESSIONS.remove(receipt);
            return null;
        }
        return session;
    }

    private static String normalizePath(HttpServletRequest req) {
        String path = req.getPathInfo();
        if (path == null || path.trim().isEmpty()) {
            return "/";
        }
        return path.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeClient(String value) {
        if (value == null) {
            return null;
        }
        String client = value.trim().toLowerCase(Locale.ROOT);
        return client.matches("^[a-f0-9]{16,64}$") ? client : null;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
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

    private static void respond(HttpServletResponse resp, int code, String state, String detail) throws IOException {
        resp.setStatus(code);
        resp.setContentType("application/json;charset=UTF-8");
        resp.setHeader("Cache-Control", "no-store");
        resp.getWriter().write("{\"state\":\"" + JsonUtil.escape(state) + "\",\"detail\":\"" + JsonUtil.escape(detail) + "\"}");
    }

    private static String randomHex(int bytes) {
        byte[] raw = new byte[bytes];
        RANDOM.nextBytes(raw);
        return hex(raw);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return hex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("sha256", e);
        }
    }

    private static String hex(byte[] raw) {
        StringBuilder builder = new StringBuilder(raw.length * 2);
        for (byte current : raw) {
            int unsigned = current & 0xff;
            if (unsigned < 0x10) {
                builder.append('0');
            }
            builder.append(Integer.toHexString(unsigned));
        }
        return builder.toString();
    }

    private static final class Session {
        private final String client;
        private final String marker;
        private final String ticket;
        private final long createdAt;
        private String digest;
        private long exportedAt;
        private boolean committed;

        private Session(String client, String marker, String ticket, long createdAt) {
            this.client = client;
            this.marker = marker;
            this.ticket = ticket;
            this.createdAt = createdAt;
        }
    }
}
