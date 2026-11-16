package dev.ngb.survey.webhook.adapter.endpoint.persistence;

import static dev.ngb.survey_jooq.Tables.WEBHOOK_ENDPOINTS;

import dev.ngb.survey.webhook.application.endpoint.query.EndpointReadRepository;
import dev.ngb.survey.webhook.application.endpoint.query.WebhookEndpointView;
import dev.ngb.survey.webhook.domain.endpoint.EndpointStatus;
import dev.ngb.survey_jooq.tables.records.WebhookEndpointsRecord;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;

/** Selects endpoints straight into the view; the domain aggregate is never built. */
public class JooqWebhookEndpointReadRepository implements EndpointReadRepository {

    private final DSLContext dsl;

    public JooqWebhookEndpointReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<WebhookEndpointView> list() {
        return dsl.selectFrom(WEBHOOK_ENDPOINTS)
                .orderBy(WEBHOOK_ENDPOINTS.CREATED_AT, WEBHOOK_ENDPOINTS.ID)
                .fetch(JooqWebhookEndpointReadRepository::toView);
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
