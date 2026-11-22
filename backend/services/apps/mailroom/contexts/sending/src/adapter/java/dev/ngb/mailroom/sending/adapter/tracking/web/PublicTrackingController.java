package dev.ngb.mailroom.sending.adapter.tracking.web;

import dev.ngb.core.security.Caller;
import dev.ngb.core.security.Callers;
import dev.ngb.core.security.PublicEndpoint;
import dev.ngb.mailroom.sending.application.tracking.command.RecordClickUseCase;
import dev.ngb.mailroom.sending.application.tracking.command.RecordOpenUseCase;
import dev.ngb.mailroom.sending.application.tracking.command.UnsubscribeUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.util.Base64;
import java.util.Optional;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The suite's first unauthenticated surface. Every path is reachable without a suite token; the tenant scope comes
 * from the verified token alone, and an invalid token is indistinguishable from a valid one.
 *
 * <p>The click endpoint redirects only to a URL recorded at render time and never reads a URL from the request, so
 * the suite domain cannot become an open redirect. Nothing here logs a token, a URL or an address.
 *
 * <p>The mapping carries both spellings of the public prefix. Compose strips {@code /email} at Caddy, so a recipient's
 * link arrives as {@code /t/...}; the ALB cannot strip a prefix, so the same link arrives as {@code /mailroom/t/...}.
 * Serving both is what keeps a pixel, a click and an unsubscribe working in either runtime.
 */
@Tag(
        name = "Public tracking",
        description =
                "Unauthenticated endpoints a recipient's mail client hits: open pixel, tracked link and one-click "
                + "unsubscribe.")
@RestController
@RequestMapping({"/t", "/mailroom/t"})
@Callers(Caller.PUBLIC)
class PublicTrackingController {

    /** A 1x1 transparent GIF. */
    private static final byte[] PIXEL = Base64.getDecoder()
            .decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7");

    private static final String UNSUBSCRIBE_PAGE = """
            <!doctype html>
            <html lang="en"><head><meta charset="utf-8"><title>Unsubscribe</title></head>
            <body>
            <h1>Unsubscribe</h1>
            <p>Confirm that you no longer want to receive marketing email from this sender.</p>
            <form method="post"><button type="submit">Unsubscribe</button></form>
            </body></html>
            """;

    private static final String UNSUBSCRIBED_PAGE = """
            <!doctype html>
            <html lang="en"><head><meta charset="utf-8"><title>Unsubscribed</title></head>
            <body><h1>Unsubscribed</h1><p>You will not receive further marketing email from this sender.</p></body></html>
            """;

    private static final String EXPIRED_PAGE = """
            <!doctype html>
            <html lang="en"><head><meta charset="utf-8"><title>Link expired</title></head>
            <body><h1>Link expired</h1><p>This link is no longer available.</p></body></html>
            """;

    private final RecordOpenUseCase open;
    private final RecordClickUseCase click;
    private final UnsubscribeUseCase unsubscribe;

    PublicTrackingController(
            RecordOpenUseCase open, RecordClickUseCase click, UnsubscribeUseCase unsubscribe) {
        this.open = open;
        this.click = click;
        this.unsubscribe = unsubscribe;
    }

    /** Accepts {@code {token}} or {@code {token}.gif}; the GIF is returned whatever the token says. */
    @Operation(
            summary = "Record an email open",
            description =
                    "The tracking pixel a recipient's mail client loads. It always answers the same transparent GIF, "
                    + "whatever the token says, so a bad token is indistinguishable from a good one. A valid token records "
                    + "an open, at most sixty a minute per email. Public: no suite token is needed.")
    @GetMapping("/o/{token}")
    @PublicEndpoint
    ResponseEntity<byte[]> open(@PathVariable String token) {
        open.record(stripGif(token));
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_GIF)
                .cacheControl(CacheControl.noStore())
                .body(PIXEL);
    }

    @Operation(
            summary = "Follow a tracked link",
            description =
                    "Redirects to the URL recorded when the email was rendered and records the click; it never reads a "
                    + "URL from the request, so it cannot be used as an open redirect. A token whose link was purged shows "
                    + "a \"link expired\" page. Public: no suite token is needed.")
    @GetMapping("/c/{token}")
    @PublicEndpoint
    ResponseEntity<?> click(@PathVariable String token) {
        Optional<String> target = click.click(token);
        if (target.isPresent()) {
            try {
                return ResponseEntity.status(302)
                        .location(URI.create(target.get()))
                        .cacheControl(CacheControl.noStore())
                        .build();
            } catch (IllegalArgumentException malformed) {
                // A stored URL that cannot be a Location is not this recipient's problem to debug; it answers like a
                // purged link instead of a server error.
            }
        }
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .cacheControl(CacheControl.noStore())
                .body(EXPIRED_PAGE);
    }

    @Operation(
            summary = "Show the unsubscribe page",
            description =
                    "The page a recipient opens from the unsubscribe link: it asks the recipient to confirm. It changes "
                    + "nothing itself. Public: no suite token is needed.")
    @GetMapping("/u/{token}")
    @PublicEndpoint
    ResponseEntity<String> unsubscribePage(@PathVariable String token) {
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .cacheControl(CacheControl.noStore())
                .body(UNSUBSCRIBE_PAGE);
    }

    @Operation(
            summary = "Unsubscribe a recipient",
            description =
                    "The one-click unsubscribe of RFC 8058. It adds a marketing suppression and flips the receiver's "
                    + "status, idempotently, and never stops transactional mail. Public: no suite token is needed.")
    @PostMapping("/u/{token}")
    @PublicEndpoint
    ResponseEntity<String> unsubscribe(@PathVariable String token) {
        unsubscribe.unsubscribe(token);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .cacheControl(CacheControl.noStore())
                .body(UNSUBSCRIBED_PAGE);
    }

    private static String stripGif(String token) {
        return token.endsWith(".gif") ? token.substring(0, token.length() - 4) : token;
    }
}
