package dev.ngb.workflow.connections.application.call.port;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** One outbound call, already authenticated and checked; the transport only moves bytes. */
public interface HttpTransport {

    /**
     * @param request the method, the absolute URL, the headers to set and the body bytes
     * @return the outcome; a refused destination, a timeout or a bad status is a result, never an exception
     */
    Response send(Request request);

    record Request(
            String method, String url, Map<String, String> headers, Object body, Duration timeout, int maxBodyBytes) {}

    record Response(int status, Map<String, List<String>> headers, String body, String error) {}
}
