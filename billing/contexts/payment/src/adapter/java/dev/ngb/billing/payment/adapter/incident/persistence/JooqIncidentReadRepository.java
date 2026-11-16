package dev.ngb.billing.payment.adapter.incident.persistence;

import static dev.ngb.billing_jooq.Tables.STRIPE_INCIDENTS;

import dev.ngb.billing.payment.application.incident.query.IncidentListing;
import dev.ngb.billing.payment.application.incident.query.IncidentReadRepository;
import dev.ngb.billing.payment.application.incident.query.IncidentView;
import dev.ngb.billing.payment.domain.incident.IncidentKind;
import dev.ngb.billing_jooq.tables.records.StripeIncidentsRecord;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.JooqLists;
import dev.ngb.core.spring.query.ListBinding;
import org.jooq.DSLContext;

/** Selects incidents straight into views. */
public class JooqIncidentReadRepository implements IncidentReadRepository {

    /** The incident fields a caller may filter and sort on, bound to their columns. */
    private static final ListBinding BINDING = ListBinding.of(IncidentListing.SCHEMA)
            .column("id", STRIPE_INCIDENTS.ID)
            .column("kind", STRIPE_INCIDENTS.KIND)
            .column("subject", STRIPE_INCIDENTS.SUBJECT)
            .column("occurrences", STRIPE_INCIDENTS.OCCURRENCES)
            .column("created_at", STRIPE_INCIDENTS.CREATED_AT)
            .column("last_seen_at", STRIPE_INCIDENTS.LAST_SEEN_AT)
            .column("resolved_at", STRIPE_INCIDENTS.RESOLVED_AT)
            .build();

    private final DSLContext dsl;

    /** @return the binding of the incident listing, for the test that checks every sortable field has an index */
    public static ListBinding listBinding() {
        return BINDING;
    }

    public JooqIncidentReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Page<IncidentView> list(ListQuery query) {
        return JooqLists.fetch(dsl, STRIPE_INCIDENTS, org.jooq.impl.DSL.noCondition(), BINDING, query,
                JooqIncidentReadRepository::toView);
    }

    private static IncidentView toView(StripeIncidentsRecord row) {
        return new IncidentView(
                row.getId(),
                IncidentKind.valueOf(row.getKind()),
                row.getSubject(),
                row.getDetail(),
                row.getOccurrences(),
                row.getCreatedAt(),
                row.getLastSeenAt(),
                row.getResolvedAt());
    }
}
