package dev.ngb.notification.query;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.query.FieldSpec;
import dev.ngb.core.query.FieldType;
import dev.ngb.core.query.FilterSchema;
import dev.ngb.core.query.Op;
import dev.ngb.core.spring.query.ListBinding;
import dev.ngb.core.spring.query.ListingIndexCoverage;
import dev.ngb.notification.Application;
import dev.ngb.notification.dispatch.adapter.notification.persistence.JooqDispatchReadRepository;
import dev.ngb.notification.dispatch.adapter.notification.persistence.JooqNotificationReadRepository;
import dev.ngb.notification.support.PostgresContainerConfig;
import dev.ngb.notification_jooq.Tables;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Every listing sorts only on fields that lead an index of their table, so a sort a caller asks for never scans the
 * whole table, and searches a text field with contains only where a trigram index serves it. Runs against the migrated
 * schema.
 */
@SpringBootTest(classes = Application.class)
@Import(PostgresContainerConfig.class)
class ListingIndexIntegrationTest {

    @Autowired DSLContext dsl;

    @Test
    void everyListingIsCovered() {
        Map<String, ListBinding> listings = Map.of(
                "inbox", JooqNotificationReadRepository.listBinding(),
                "dispatch", JooqDispatchReadRepository.listBinding());

        listings.forEach((name, binding) ->
                assertThat(ListingIndexCoverage.missing(dsl, binding)).as("the %s listing", name).isEmpty());
    }

    @Test
    void aSortableFieldWithoutAnIndexIsReported() {
        FilterSchema schema = FilterSchema.of("id", "id",
                FieldSpec.of("id", FieldType.UUID).asSortable(),
                FieldSpec.of("locale", FieldType.STRING).asSortable());
        ListBinding binding = ListBinding.of(schema)
                .column("id", Tables.NOTIFICATIONS.ID)
                .column("locale", Tables.NOTIFICATIONS.LOCALE)
                .build();

        assertThat(ListingIndexCoverage.missing(dsl, binding))
                .singleElement()
                .asString()
                .contains("locale")
                .contains("notifications");
    }

    @Test
    void aContainsFieldWithoutATrigramIndexIsReported() {
        FilterSchema schema = FilterSchema.of("id", "id",
                FieldSpec.of("id", FieldType.UUID).asSortable(),
                FieldSpec.of("title", FieldType.STRING).with(Op.CONTAINS));
        ListBinding binding = ListBinding.of(schema)
                .column("id", Tables.NOTIFICATIONS.ID)
                .column("title", Tables.NOTIFICATIONS.TITLE)
                .build();

        assertThat(ListingIndexCoverage.missing(dsl, binding))
                .singleElement()
                .asString()
                .contains("title")
                .contains("gin_trgm_ops");
    }
}
