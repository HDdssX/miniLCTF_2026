package ctf.ghostvalve.bridge;

import ctf.ghostvalve.market.ThemeLedger;
import java.io.IOException;
import javax.servlet.ServletException;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;

public final class ReviewValve extends ValveBase {
    private static final String OP_HEADER = "X-Theme-Mode";
    private static final String TOKEN_HEADER = "X-Theme-Key";
    private static final String MARKER_HEADER = "X-Theme-Tag";
    private static final String PROOF_HEADER = "X-Theme-Match";
    private static final String FLOW_HEADER = "X-Theme-Flow";
    private static final String COOKIE_NAME = "theme_flow";
    private static final String PUBLISH_COOKIE = "theme_publish";

    private final String token;

    public ReviewValve(String token) {
        super(true);
        this.token = token;
    }

    @Override
    public void invoke(Request request, Response response) throws IOException, ServletException {
        String op = request.getHeader(OP_HEADER);
        if (op == null || op.isEmpty()) {
            if (getNext() != null) {
                getNext().invoke(request, response);
            }
            return;
        }
        if (!token.equals(request.getHeader(TOKEN_HEADER))) {
            if (getNext() != null) {
                getNext().invoke(request, response);
                return;
            }
            respond(response, 404, "missing key");
            return;
        }
        if ("peek".equals(op)) {
            response.setStatus(204);
            ThemeLedger.Pass bundle = ThemeLedger.fetch(token);
            if (bundle != null) {
                response.addHeader("Set-Cookie", COOKIE_NAME + "=" + bundle.getValue() + "; Path=/");
                response.addHeader(MARKER_HEADER, bundle.getTag());
                if (bundle.getWitness() != null && !bundle.getWitness().trim().isEmpty()) {
                    response.addHeader(FLOW_HEADER, bundle.getWitness().trim());
                }
            } else {
                response.addHeader("Set-Cookie", COOKIE_NAME + "=1; Path=/");
            }
            return;
        }
        if ("apply".equals(op)) {
            String cookieValue = readCookie(request, COOKIE_NAME);
            String proof = request.getHeader(PROOF_HEADER);
            String marker = request.getHeader(MARKER_HEADER);
            try {
                if (ThemeLedger.confirm(token, cookieValue, cleanupProof(proof), cleanupProof(marker))) {
                    String publishPass = ThemeAssemblyBootstrap.peekPendingReceipt(token);
                    response.setStatus(204);
                    response.addHeader("Set-Cookie", COOKIE_NAME + "=ready; Path=/");
                    if (publishPass != null && !publishPass.trim().isEmpty()) {
                        response.addHeader("Set-Cookie", PUBLISH_COOKIE + "=" + publishPass + "; Path=/; HttpOnly");
                    }
                    response.addHeader(MARKER_HEADER, "ready");
                    return;
                }
            } catch (Exception e) {
                throw new IOException("apply failed", e);
            }
            respond(response, 409, "queued");
            return;
        }
        respond(response, 404, "bad");
    }

    private static String cleanupProof(String proof) {
        if (proof == null) {
            return null;
        }
        String value = proof.trim();
        if (value.startsWith("W/")) {
            value = value.substring(2).trim();
        }
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static String readCookie(Request request, String name) {
        javax.servlet.http.Cookie[] cookies = request.getCookies();
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

    private static void respond(Response response, int status, String text) throws IOException {
        response.setStatus(status);
        response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write(text);
    }
}
