package dev.ngb.billing.payment.adapter.stripe.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import dev.ngb.core.http.HttpHeaders;
import dev.ngb.billing.payment.application.stripe.command.ReceiveStripeWebhookUseCase;
import dev.ngb.billing.payment.domain.stripe.StripeConfigurationId;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.security.PublicEndpoint;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where Stripe delivers events. These endpoints take no login: the signature of the raw body is the proof, and
 * it is checked before anything in the body is read. The body is read as bytes, up to a fixed limit, so it reaches
 * the verifier exactly as Stripe sent it.
 */
@Tag(
        name = "Stripe webhooks",
        description =
                "Where Stripe delivers events. No login: the signature of the raw body is the proof.")
@Profile("api")
@RestController
public class StripeWebhookController {

    /** Stripe events are a few kilobytes; anything near this is not one, and is refused before any work is done. */
    private static final int MAX_BODY_BYTES = 512 * 1024;

    private final ReceiveStripeWebhookUseCase receive;

    public StripeWebhookController(ReceiveStripeWebhookUseCase receive) {
        this.receive = receive;
    }

    /** A Direct webhook: the Tenant creates this endpoint in its own Stripe Dashboard and keeps the signing secret it is shown. */
    @Operation(
            summary = "Receive a direct Stripe webhook",
            description =
                    "Where Stripe delivers events for a tenant's own Stripe account; the tenant creates this endpoint in "
                    + "its own Stripe Dashboard. The signature of the raw body is the proof and is checked before anything "
                    + "in the body is read. Public: no suite token is needed.")
    @PostMapping("/stripe/webhooks/direct/{configurationId}")
    @PublicEndpoint
    public Map<String, String> direct(
            @PathVariable String configurationId, @RequestHeader(value = HttpHeaders.STRIPE_SIGNATURE, required = false) String signature,
            HttpServletRequest request) throws IOException {
        StripeConfigurationId id;
        try {
            id = StripeConfigurationId.of(configurationId);
        } catch (IllegalArgumentException e) {
            throw new NotFoundException("unknown webhook endpoint");
        }
        return answer(receive.receiveDirect(id, text(request), signature));
    }

    /** The Platform's Connect webhook, one endpoint for every connected account. */
    @Operation(
            summary = "Receive a Stripe Connect webhook",
            description =
                    "The platform's Connect webhook, one endpoint for every connected account; the tenant comes from "
                    + "Billing's own routing table, never from the request. The signature of the raw body is the proof. "
                    + "Public: no suite token is needed.")
    @PostMapping("/stripe/webhooks/connect")
    @PublicEndpoint
    public Map<String, String> connect(@RequestHeader(value = HttpHeaders.STRIPE_SIGNATURE, required = false) String signature, HttpServletRequest request)
            throws IOException {
        return answer(receive.receiveConnect(text(request), signature));
    }

    /** Reads at most one byte more than the limit, so an oversized body is refused without being held in memory. */
    private static String text(HttpServletRequest request) throws IOException {
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            throw new IllegalArgumentException("the body is too large to be a Stripe event");
        }
        return new String(body, StandardCharsets.UTF_8);
    }

    private static Map<String, String> answer(ReceiveStripeWebhookUseCase.Receipt receipt) {
        return Map.of("status", receipt.disposition().name().toLowerCase(Locale.ROOT));
    }
}
