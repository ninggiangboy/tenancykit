package dev.ngb.billing.boot.replica.feign;

import dev.ngb.core.feign.FeignClients;
import dev.ngb.core.feign.FeignFailures;
import dev.ngb.core.feign.FeignSettings;
import dev.ngb.core.feign.TokenProvider;
import dev.ngb.core.sync.SnapshotClient;
import feign.RetryableException;
import java.util.List;

/** The snapshot API over Feign, so a page is a declared call rather than a copied URI. */
public final class FeignSnapshotTransport implements SnapshotClient.SnapshotTransport {

    private final SnapshotFeignApi api;
    private final TokenProvider tokens;
    private final FeignFailures failures;

    private FeignSnapshotTransport(SnapshotFeignApi api, TokenProvider tokens, FeignFailures failures) {
        this.api = api;
        this.tokens = tokens;
        this.failures = failures;
    }

    /** Builds the client this transport pages with: one proxy for the credential this replica owns. */
    public static FeignSnapshotTransport create(
            FeignSettings settings, TokenProvider tokens, FeignFailures failures) {
        SnapshotFeignApi api = FeignClients.create(
                SnapshotFeignApi.class, settings, tokens, failures, new PlatformSnapshotErrorMapper(failures));
        return new FeignSnapshotTransport(api, tokens, failures);
    }

    @Override
    public String serviceToken() {
        return tokens.token();
    }

    /**
     * The client's {@code TokenClient} puts the credential on every request, so the bearer token the snapshot client
     * passes here is already applied; the parameter stays because it belongs to the port every replica consumer shares.
     */
    @Override
    public SnapshotClient.Page page(String stream, String after, int limit, String bearerToken) {
        try {
            SnapshotFeignApi.SnapshotPage page = api.page(stream, after, limit);
            if (page == null) {
                return new SnapshotClient.Page(List.of(), null);
            }
            return new SnapshotClient.Page(page.items(), page.next());
        } catch (RetryableException e) {
            throw failures.unavailable("Platform could not be reached", e);
        }
    }
}
