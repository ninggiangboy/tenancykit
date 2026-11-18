package dev.ngb.core.suite.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * The one refusal body and the one health bypass both suite filters use, so a request refused by either filter sees
 * the same media type, the same shape and the same status. Health probes reach the pod by IP, so they carry no tenant
 * hostname and always run on the platform scope.
 */
public final class SuiteProblems {

    /** The only media type these filters answer with. */
    public static final String PROBLEM_JSON = "application/problem+json";

    /** The type of a hostname no tenant owns. */
    public static final String NOT_FOUND = "not-found";

    /** The type of a tenant that has not enabled the app. */
    public static final String APP_NOT_ENABLED = "app-not-enabled";

    private SuiteProblems() {}

    /** Whether the request is a health probe, which is answered on the platform scope whatever the host is. */
    public static boolean isHealthProbe(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator/health");
    }

    /** Writes one RFC 9457 body: the type, the sentence a caller reads and the status the response carries. */
    public static void write(HttpServletResponse response, int status, String type, String title) throws IOException {
        response.setStatus(status);
        response.setContentType(PROBLEM_JSON);
        String body = "{\"type\":\"" + escape(type) + "\",\"title\":\"" + escape(title) + "\",\"status\":" + status + "}";
        response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
