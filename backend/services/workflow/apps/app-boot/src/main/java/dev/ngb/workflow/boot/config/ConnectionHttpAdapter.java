package dev.ngb.workflow.boot.config;

import dev.ngb.workflow.connections.api.call.HttpCaller;
import dev.ngb.workflow.connections.api.call.OutboundRequest;
import dev.ngb.workflow.connections.api.call.OutboundResponse;
import dev.ngb.workflow.connections.application.call.exception.CallNotAllowedException;
import dev.ngb.workflow.connections.application.credential.exception.CredentialUnavailableException;
import dev.ngb.workflow.runtime.domain.step.OutboundHttp;
import java.util.Map;

/**
 * Gives the runtime the outbound door without a context dependency: the call belongs to the connections context, the
 * engine only sees its own port. Boot is the one place allowed to know both.
 */
class ConnectionHttpAdapter implements OutboundHttp {

    private final HttpCaller caller;

    ConnectionHttpAdapter(HttpCaller caller) {
        this.caller = caller;
    }

    @Override
    public Result send(Call call) {
        try {
            OutboundResponse response = caller.call(new OutboundRequest(
                    call.method(),
                    call.app(),
                    call.connection(),
                    call.path(),
                    call.url(),
                    call.headers(),
                    call.body(),
                    call.idempotencyKey(),
                    call.timeout()));
            return new Result(response.status(), response.headers(), response.body(), response.error());
        } catch (CredentialUnavailableException | CallNotAllowedException refused) {
            return new Result(0, Map.of(), null, refused.getMessage());
        } catch (RuntimeException failure) {
            return new Result(0, Map.of(), null, "call_failed");
        }
    }
}
