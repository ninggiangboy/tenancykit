package dev.ngb.workflow.connections.adapter.call.http;

import dev.ngb.core.webhook.BlockedDestinationException;
import dev.ngb.core.webhook.GuardedDnsResolver;
import dev.ngb.workflow.connections.application.call.port.HttpTransport;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.util.Timeout;
import tools.jackson.databind.json.JsonMapper;

/**
 * The only code that opens a socket to a Tenant-chosen address.
 *
 * <p>The guard is the client's own resolver, so a host that resolves to loopback, link-local, a private range or the
 * metadata address is refused before any connection is made. Redirects are not followed by the client; a 3xx is
 * returned as a result, so a redirect cannot carry the call somewhere the resolver never checked. Bodies are capped at
 * the caller's limit.
 */
public class GuardedHttpTransport implements HttpTransport {

    private static final int MAX_REDIRECTS = 1;

    private final CloseableHttpClient client;
    private final JsonMapper json;

    /**
     * @param allowNonPublicTargets development only: app base URLs inside the compose network are private
     */
    public GuardedHttpTransport(
            boolean allowNonPublicTargets,
            String userAgent,
            int maxConnTotal,
            int maxConnPerRoute,
            Duration connectTimeout,
            Duration readTimeout,
            JsonMapper json) {
        ConnectionConfig connection = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(connectTimeout.toMillis()))
                .setSocketTimeout(Timeout.ofMilliseconds(readTimeout.toMillis()))
                .build();
        this.client = HttpClients.custom()
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                        .setDnsResolver(new GuardedDnsResolver(allowNonPublicTargets))
                        .setDefaultConnectionConfig(connection)
                        .setMaxConnTotal(maxConnTotal)
                        .setMaxConnPerRoute(maxConnPerRoute)
                        .build())
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setResponseTimeout(Timeout.ofMilliseconds(readTimeout.toMillis()))
                        .build())
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .disableCookieManagement()
                .setUserAgent(userAgent)
                .build();
        this.json = json;
    }

    @Override
    public Response send(Request request) {
        return send(request, 0);
    }

    private Response send(Request request, int redirects) {
        try {
            URI target = URI.create(encode(request.url()));
            if (!target.isAbsolute() || target.getHost() == null) {
                return new Response(0, Map.of(), null, "invalid_url");
            }
            HttpUriRequestBase call = new HttpUriRequestBase(request.method(), target);
            request.headers().forEach(call::setHeader);
            if (request.body() != null) {
                byte[] bytes = request.body() instanceof String text
                        ? text.getBytes(StandardCharsets.UTF_8)
                        : json.writeValueAsBytes(request.body());
                call.setEntity(new ByteArrayEntity(bytes, null));
            }
            try (ClassicHttpResponse response = client.executeOpen(null, call, null)) {
                int code = response.getCode();
                String location = response.getFirstHeader("Location") == null
                        ? null
                        : response.getFirstHeader("Location").getValue();
                if (redirects < MAX_REDIRECTS && isFollowable(request.method(), code) && location != null) {
                    // The next hop is resolved by the same guard, so a redirect cannot carry the call somewhere the
                    // resolver never checked.
                    URI next = target.resolve(location);
                    if (!next.isAbsolute() || next.getHost() == null) {
                        return new Response(0, Map.of(), null, "invalid_redirect");
                    }
                    boolean toGet = code == 303 || (code == 301 || code == 302) && !"HEAD".equals(request.method());
                    return send(
                            new Request(
                                    toGet ? "GET" : request.method(),
                                    next.toString(),
                                    request.headers(),
                                    toGet ? null : request.body(),
                                    request.timeout(),
                                    request.maxBodyBytes()),
                            redirects + 1);
                }
                return new Response(code, headers(response), body(response.getEntity(), request.maxBodyBytes()), null);
            }
        } catch (IOException | RuntimeException failure) {
            return new Response(0, Map.of(), null, describe(failure));
        }
    }

    private String body(HttpEntity entity, int maxBodyBytes) throws IOException {
        if (entity == null) {
            return null;
        }
        try (InputStream in = entity.getContent()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            while (out.size() < maxBodyBytes) {
                int read = in.read(buffer, 0, Math.min(buffer.length, maxBodyBytes - out.size()));
                if (read < 0) {
                    break;
                }
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    private static Map<String, List<String>> headers(ClassicHttpResponse response) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (Header header : response.getHeaders()) {
            headers.computeIfAbsent(header.getName(), name -> new java.util.ArrayList<>()).add(header.getValue());
        }
        return headers;
    }

    /** A space in a path is the common mistake; the rest of an invalid URL is refused by {@link URI#create}. */
    private static String encode(String url) {
        return url.replace(" ", URLEncoder.encode(" ", StandardCharsets.UTF_8));
    }

    private static boolean isFollowable(String method, int status) {
        boolean safe = "GET".equals(method) || "HEAD".equals(method);
        return safe && (status == 301 || status == 302 || status == 303 || status == 307 || status == 308);
    }

    private static String describe(Exception failure) {
        if (failure instanceof BlockedDestinationException) {
            return "blocked_destination";
        }
        if (failure instanceof UnknownHostException) {
            return "dns_failure";
        }
        if (failure instanceof SocketTimeoutException) {
            return "timeout";
        }
        if (failure instanceof IllegalArgumentException) {
            return "invalid_url";
        }
        return failure instanceof IOException ? "connection_failed" : "send_failed";
    }
}
