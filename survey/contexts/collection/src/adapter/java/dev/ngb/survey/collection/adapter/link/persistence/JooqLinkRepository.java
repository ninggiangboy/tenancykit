package dev.ngb.survey.collection.adapter.link.persistence;

import static dev.ngb.survey_jooq.Tables.SURVEY_LINKS;

import dev.ngb.core.id.TenantId;
import dev.ngb.survey.collection.application.link.port.LinkRepository;
import dev.ngb.survey.collection.domain.link.LinkKind;
import dev.ngb.survey.collection.domain.link.LinkStatus;
import dev.ngb.survey.collection.domain.link.SurveyLink;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Query;
import org.jooq.Record;

/** The link store: one row per link, inserted once and moved to revoked, never deleted. */
public class JooqLinkRepository implements LinkRepository {

    private final DSLContext dsl;

    public JooqLinkRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void insert(SurveyLink link) {
        insertAll(List.of(link));
    }

    @Override
    public void insertAll(List<SurveyLink> links) {
        Query[] statements = links.stream().map(this::insertStatement).toArray(Query[]::new);
        dsl.batch(statements).execute();
    }

    private Query insertStatement(SurveyLink link) {
        return dsl.insertInto(SURVEY_LINKS)
                .set(SURVEY_LINKS.ID, link.id())
                .set(SURVEY_LINKS.TENANT_ID, link.tenantId().value())
                .set(SURVEY_LINKS.SURVEY_ID, link.surveyId())
                .set(SURVEY_LINKS.KIND, link.kind().name())
                .set(SURVEY_LINKS.SOURCE, link.source())
                .set(SURVEY_LINKS.EXTERNAL_REF, link.externalRef())
                .set(SURVEY_LINKS.EXPIRES_AT, link.expiresAt())
                .set(SURVEY_LINKS.STATUS, link.status().name())
                .set(SURVEY_LINKS.CREATED_AT, link.createdAt());
    }

    @Override
    public Optional<SurveyLink> findById(UUID linkId) {
        return dsl.selectFrom(SURVEY_LINKS)
                .where(SURVEY_LINKS.ID.eq(linkId))
                .fetchOptional(this::toLink);
    }

    @Override
    public boolean publicSourceTaken(UUID surveyId, String source) {
        return dsl.fetchCount(
                        SURVEY_LINKS,
                        SURVEY_LINKS.SURVEY_ID.eq(surveyId)
                                .and(SURVEY_LINKS.KIND.eq(LinkKind.PUBLIC.name()))
                                .and(SURVEY_LINKS.SOURCE.eq(source)))
                > 0;
    }

    @Override
    public boolean revoke(UUID linkId, UUID tenantId) {
        return dsl.update(SURVEY_LINKS)
                .set(SURVEY_LINKS.STATUS, LinkStatus.REVOKED.name())
                .where(SURVEY_LINKS.ID.eq(linkId))
                .and(SURVEY_LINKS.TENANT_ID.eq(tenantId))
                .and(SURVEY_LINKS.STATUS.eq(LinkStatus.ACTIVE.name()))
                .execute()
                > 0;
    }

    private SurveyLink toLink(Record row) {
        return new SurveyLink(
                row.get(SURVEY_LINKS.ID),
                new TenantId(row.get(SURVEY_LINKS.TENANT_ID)),
                row.get(SURVEY_LINKS.SURVEY_ID),
                LinkKind.valueOf(row.get(SURVEY_LINKS.KIND)),
                row.get(SURVEY_LINKS.SOURCE),
                row.get(SURVEY_LINKS.EXTERNAL_REF),
                row.get(SURVEY_LINKS.EXPIRES_AT),
                LinkStatus.valueOf(row.get(SURVEY_LINKS.STATUS)),
                row.get(SURVEY_LINKS.CREATED_AT));
    }
}
