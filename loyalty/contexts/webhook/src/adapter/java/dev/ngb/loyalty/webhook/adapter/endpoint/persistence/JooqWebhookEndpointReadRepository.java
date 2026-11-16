package dev.ngb.loyalty.webhook.adapter.endpoint.persistence;

import static dev.ngb.loyalty_jooq.Tables.WEBHOOK_ENDPOINTS;
import static org.jooq.impl.DSL.noCondition;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.JooqLists;
import dev.ngb.core.spring.query.ListBinding;
import dev.ngb.loyalty.webhook.application.endpoint.query.EndpointListing;
import dev.ngb.loyalty.webhook.application.endpoint.query.EndpointReadRepository;
import dev.ngb.loyalty.webhook.application.endpoint.query.WebhookEndpointView;
import dev.ngb.loyalty.webhook.domain.endpoint.EndpointStatus;
import dev.ngb.loyalty_jooq.tables.records.WebhookEndpointsRecord;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;

/** Selects endpoints straight into the view; the domain aggregate is never built. */
public class JooqWebhookEndpointReadRepository implements EndpointReadRepository {

    /** The endpoint fields a caller may filter and sort on, bound to their columns. */
    private static final ListBinding BINDING = ListBinding.of(EndpointListing.SCHEMA)
            .column("id", WEBHOOK_ENDPOINTS.ID)
            .column("url", WEBHOOK_ENDPOINTS.URL)
            .column("description", WEBHOOK_ENDPOINTS.DESCRIPTION)
            .column("status", WEBHOOK_ENDPOINTS.STATUS)
            .column("key_id", WEBHOOK_ENDPOINTS.KEY_ID)
            .column("created_at", WEBHOOK_ENDPOINTS.CREATED_AT)
            .column("updated_at", WEBHOOK_ENDPOINTS.UPDATED_AT)
            .column("secret_rotated_at", WEBHOOK_ENDPOINTS.SECRET_ROTATED_AT)
            .build();

    private final DSLContext dsl;

    /** @return the binding of the endpoint listing, for the test that checks every sortable field has an index */
    public static ListBinding listBinding() {
        return BINDING;
    }

    public JooqWebhookEndpointReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Page<WebhookEndpointView> list(ListQuery query) {
        return JooqLists.fetch(
                dsl, WEBHOOK_ENDPOINTS, noCondition(), BINDING, query, JooqWebhookEndpointReadRepository::toView);
    }

    @Override
    public Optional<WebhookEndpointView> findById(UUID id) {
        return dsl.selectFrom(WEBHOOK_ENDPOINTS)
                .where(WEBHOOK_ENDPOINTS.ID.eq(id))
                .fetchOptional(JooqWebhookEndpointReadRepository::toView);
    }

    private static WebhookEndpointView toView(WebhookEndpointsRecord row) {
        String[] eventTypes = row.getEventTypes();
        return new WebhookEndpointView(
                row.getId(),
                row.getUrl(),
                row.getDescription(),
                eventTypes == null || eventTypes.length == 0 ? null : String.join(",", Arrays.asList(eventTypes)),
                EndpointStatus.valueOf(row.getStatus()),
                row.getKeyId(),
                row.getSecretCiphertext(),
                row.getCreatedAt(),
                row.getUpdatedAt(),
                row.getSecretRotatedAt(),
                row.getVersion());
    }
}
