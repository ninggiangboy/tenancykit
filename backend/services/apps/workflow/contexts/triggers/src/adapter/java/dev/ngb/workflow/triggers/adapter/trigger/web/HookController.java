package dev.ngb.workflow.triggers.adapter.trigger.web;

import dev.ngb.core.security.Caller;
import dev.ngb.core.security.Callers;
import dev.ngb.core.security.PublicEndpoint;
import dev.ngb.workflow.triggers.application.trigger.command.ReceiveEventUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public hook a Tenant registers in a source app. The trigger's secret proves the delivery, so no suite token is
 * needed and the tenant comes from the host.
 *
 * <p>A duplicate event id is answered as accepted; a bad signature, a stale timestamp, an unknown trigger and a
 * tampered body all answer the same 401.
 */
@Tag(
        name = "Event hooks",
        description =
                "The public hook a tenant registers in a source app. The trigger's secret proves the delivery.")
@Profile("api")
@RestController
@RequestMapping("/hooks")
@Callers(Caller.PROVIDER)
public class HookController {

    public record ReceiptResponse(String eventId, int started, boolean duplicate) {}

    private final ReceiveEventUseCase receive;

    public HookController(ReceiveEventUseCase receive) {
        this.receive = receive;
    }

    @Operation(
            summary = "Receive a signed event hook",
            description =
                    "The public hook a tenant registers in a source app. The trigger's secret signs the delivery, so no "
                    + "suite token is needed, and the tenant comes from the host. A new event starts every matching trigger "
                    + "and is answered with how many started; a duplicate event id is answered as accepted and starts "
                    + "nothing. A bad signature, a stale timestamp, an unknown trigger and a tampered body all answer the "
                    + "same `401`.")
    @PostMapping("/{triggerId}")
    @PublicEndpoint(caller = Caller.PROVIDER)
    public ReceiptResponse receive(
            @PathVariable String triggerId,
            @RequestHeader Map<String, String> headers,
            @RequestBody String rawBody) {
        ReceiveEventUseCase.Receipt receipt = receive.execute(new ReceiveEventUseCase.Command(
                UUID.fromString(triggerId), headers, rawBody, document(rawBody)));
        return new ReceiptResponse(
                receipt.eventId().toString(), receipt.started(), receipt.duplicate());
    }

    /**
     * The body is parsed by the adapter, because the application layer sees no Jackson type. A body that is not JSON
     * is refused by the same {@code BadRequestException} the parser raises.
     */
    private Object document(String rawBody) {
        try {
            return JSON.convertValue(JSON.readTree(rawBody), Object.class);
        } catch (RuntimeException e) {
            throw new dev.ngb.core.error.BadRequestException("The delivery body is not JSON.");
        }
    }

    private static final tools.jackson.databind.json.JsonMapper JSON =
            tools.jackson.databind.json.JsonMapper.builder().build();
}
