package dev.ngb.survey.query;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.query.FieldSpec;
import dev.ngb.core.query.FieldType;
import dev.ngb.core.query.FilterSchema;
import dev.ngb.core.spring.query.ListBinding;
import dev.ngb.core.spring.query.ListingIndexCoverage;
import dev.ngb.survey.authoring.adapter.survey.persistence.JooqSurveyReadRepository;
import dev.ngb.survey.authoring.adapter.version.persistence.JooqVersionReadRepository;
import dev.ngb.survey.collection.adapter.link.persistence.JooqLinkReadRepository;
import dev.ngb.survey.collection.adapter.response.persistence.JooqResponseReader;
import dev.ngb.survey.insight.adapter.export.persistence.JooqExportReadRepository;
import dev.ngb.survey.support.SurveyIntegrationTest;
import dev.ngb.survey.webhook.adapter.delivery.persistence.JooqWebhookDeliveryReadRepository;
import dev.ngb.survey.webhook.adapter.endpoint.persistence.JooqWebhookEndpointReadRepository;
import dev.ngb.survey_jooq.Tables;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Every listing sorts only on fields that lead an index of their table, so a sort a caller asks for never scans the whole
 * table. Runs against the migrated schema.
 */
class ListingIndexIntegrationTest extends SurveyIntegrationTest {

    @Autowired
    DSLContext dsl;

    @Test
    void everyListingIsCovered() {
        Map<String, ListBinding> listings = Map.ofEntries(
                Map.entry("survey", JooqSurveyReadRepository.listBinding()),
                Map.entry("version", JooqVersionReadRepository.listBinding()),
                Map.entry("link", JooqLinkReadRepository.listBinding()),
                Map.entry("export", JooqExportReadRepository.listBinding()),
                Map.entry("delivery", JooqWebhookDeliveryReadRepository.listBinding()),
                Map.entry("endpoint", JooqWebhookEndpointReadRepository.listBinding()),
                Map.entry("response", JooqResponseReader.listBindings().get("response")),
                Map.entry("respondent", JooqResponseReader.listBindings().get("respondent")));

        listings.forEach((name, binding) ->
                assertThat(ListingIndexCoverage.missing(dsl, binding)).as("the %s listing", name).isEmpty());
    }

    @Test
    void aSortableFieldWithoutAnIndexIsReported() {
        FilterSchema schema = FilterSchema.of("id", "id",
                FieldSpec.of("id", FieldType.UUID).asSortable(),
                FieldSpec.of("source", FieldType.STRING).asSortable());
        ListBinding binding = ListBinding.of(schema)
                .column("id", Tables.RESPONSES.ID)
                .column("source", Tables.RESPONSES.SOURCE)
                .build();

        assertThat(ListingIndexCoverage.missing(dsl, binding)).singleElement().asString().contains("source").contains("responses");
    }
}
