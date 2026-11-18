package dev.ngb.workflow.runtime.domain.step;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * The engine's own view of an outbound call. The runtime never opens a socket: boot implements this by delegating to the
 * connections context, which owns the guards, the credentials and the redaction rules.
 */
public interface OutboundHttp {

    /**
     * @param app an app of the suite, authenticated with a token minted for that audience
     * @param connection a Tenant connection, which supplies the credential and fixes the host
     * @param url an absolute URL, used only when neither of the other two is named
     */
    record Call(
            String method,
            String app,
            String connection,
            String path,
            String url,
            Map<String, String> headers,
            Object body,
            String idempotencyKey,
            Duration timeout) {}

    /** {@code status} is 0 when no response arrived; {@code error} then says why. */
    record Result(int status, Map<String, List<String>> headers, String body, String error) {
        public boolean arrived() {
            return status > 0 && error == null;
        }
    }

    Result send(Call call);
}
