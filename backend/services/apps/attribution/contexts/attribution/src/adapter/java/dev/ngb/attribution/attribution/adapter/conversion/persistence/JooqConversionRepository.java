package dev.ngb.attribution.attribution.adapter.conversion.persistence;

import static dev.ngb.attribution_jooq.Tables.CONVERSION_CREDITS;
import static dev.ngb.attribution_jooq.Tables.CONVERSIONS;

import dev.ngb.attribution.attribution.application.conversion.port.ConversionRepository;
import dev.ngb.attribution.attribution.domain.credit.CreditEngine;
import dev.ngb.attribution.attribution.domain.conversion.Conversion;
import dev.ngb.attribution.attribution.domain.conversion.ConversionSource;
import dev.ngb.attribution.attribution.domain.conversion.CreditStatus;
import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The conversion and credit store.
 *
 * <p>A conversion's chain is written as JSON when it settles, so a recompute reads the snapshot rather than re-deriving
 * from raw events. Credits are replaced as a set — delete then insert — so a recompute never leaves a stale credit
 * beside a fresh one, and the sum of the stored credits is always the conversion's value.
 */
public class JooqConversionRepository implements ConversionRepository {

    private final DSLContext dsl;
    private final JsonMapper json;

    public JooqConversionRepository(DSLContext dsl, JsonMapper json) {
        this.dsl = dsl;
        this.json = json;
    }

    @Override
    public void insert(Conversion conversion) {
        dsl.insertInto(CONVERSIONS)
                .set(CONVERSIONS.ID, conversion.id())
                .set(CONVERSIONS.TENANT_ID, conversion.tenantId().value())
                .set(CONVERSIONS.GOAL_ID, conversion.goalId())
                .set(CONVERSIONS.CLICK_ID, conversion.clickId())
                .set(CONVERSIONS.VISITOR_ID, conversion.visitorId())
                .set(CONVERSIONS.VALUE, conversion.value())
                .set(CONVERSIONS.CURRENCY, conversion.currency())
                .set(CONVERSIONS.OCCURRED_AT, conversion.occurredAt())
                .set(CONVERSIONS.SOURCE, conversion.source().name())
                .set(CONVERSIONS.IDEMPOTENCY_KEY, conversion.idempotencyKey())
                .set(CONVERSIONS.CREDIT_STATUS, conversion.creditStatus().name())
                .set(CONVERSIONS.OWNING_CAMPAIGN_ID, conversion.owningCampaignId())
                .set(CONVERSIONS.TOUCH_CHAIN, JSONB.valueOf(writeChain(conversion.touchChain())))
                .set(CONVERSIONS.CREATED_AT, conversion.createdAt())
                .execute();
    }

    @Override
    public Optional<Conversion> findByIdempotencyKey(String idempotencyKey) {
        return dsl.selectFrom(CONVERSIONS)
                .where(CONVERSIONS.IDEMPOTENCY_KEY.eq(idempotencyKey))
                .fetchOptional(this::toConversion);
    }

    @Override
    public Optional<Conversion> findById(UUID conversionId) {
        return dsl.selectFrom(CONVERSIONS)
                .where(CONVERSIONS.ID.eq(conversionId))
                .fetchOptional(this::toConversion);
    }

    @Override
    public void settle(Conversion conversion, CreditEngine.Model model, List<CreditEngine.Credit> credits) {
        dsl.update(CONVERSIONS)
                .set(CONVERSIONS.CREDIT_STATUS, conversion.creditStatus().name())
                .set(CONVERSIONS.OWNING_CAMPAIGN_ID, conversion.owningCampaignId())
                .set(CONVERSIONS.TOUCH_CHAIN, JSONB.valueOf(writeChain(conversion.touchChain())))
                .set(CONVERSIONS.COMPUTED_AT, conversion.computedAt())
                .where(CONVERSIONS.ID.eq(conversion.id()))
                .execute();
        writeCredits(conversion, model, credits);
    }

    @Override
    public void replaceCredits(Conversion conversion, CreditEngine.Model model, List<CreditEngine.Credit> credits) {
        dsl.update(CONVERSIONS)
                .set(CONVERSIONS.CREDIT_STATUS, conversion.creditStatus().name())
                .set(CONVERSIONS.OWNING_CAMPAIGN_ID, conversion.owningCampaignId())
                .set(CONVERSIONS.COMPUTED_AT, conversion.computedAt())
                .where(CONVERSIONS.ID.eq(conversion.id()))
                .execute();
        // The old credits are replaced as a set, so a recompute cannot leave one beside its replacement.
        dsl.deleteFrom(CONVERSION_CREDITS)
                .where(CONVERSION_CREDITS.CONVERSION_ID.eq(conversion.id()))
                .execute();
        writeCredits(conversion, model, credits);
    }

    @Override
    public List<CreditEngine.Credit> credits(UUID conversionId) {
        return dsl.selectFrom(CONVERSION_CREDITS)
                .where(CONVERSION_CREDITS.CONVERSION_ID.eq(conversionId))
                .orderBy(CONVERSION_CREDITS.CLICK_ID.asc())
                .fetch(row -> new CreditEngine.Credit(
                        row.getClickId().toString(),
                        row.getLinkId().toString(),
                        row.getCampaignId() == null ? null : row.getCampaignId().toString(),
                        row.getWeight(),
                        row.getCredit()));
    }

    private void writeCredits(Conversion conversion, CreditEngine.Model model, List<CreditEngine.Credit> credits) {
        for (CreditEngine.Credit credit : credits) {
            dsl.insertInto(CONVERSION_CREDITS)
                    .set(CONVERSION_CREDITS.TENANT_ID, conversion.tenantId().value())
                    .set(CONVERSION_CREDITS.CONVERSION_ID, conversion.id())
                    .set(CONVERSION_CREDITS.CLICK_ID, UUID.fromString(credit.clickId()))
                    .set(CONVERSION_CREDITS.LINK_ID, UUID.fromString(credit.linkId()))
                    .set(
                            CONVERSION_CREDITS.CAMPAIGN_ID,
                            credit.campaignId() == null ? null : UUID.fromString(credit.campaignId()))
                    .set(CONVERSION_CREDITS.MODEL, model.name())
                    .set(CONVERSION_CREDITS.WEIGHT, credit.weight())
                    .set(CONVERSION_CREDITS.CREDIT, credit.credit())
                    .onConflict(
                            CONVERSION_CREDITS.TENANT_ID,
                            CONVERSION_CREDITS.CONVERSION_ID,
                            CONVERSION_CREDITS.CLICK_ID)
                    .doUpdate()
                    .set(CONVERSION_CREDITS.WEIGHT, credit.weight())
                    .set(CONVERSION_CREDITS.CREDIT, credit.credit())
                    .execute();
        }
    }

    private String writeChain(List<CreditEngine.Touch> chain) {
        return json.writeValueAsString(chain.stream().map(touch -> new ChainTouch(
                touch.clickId(), touch.linkId(), touch.campaignId(), touch.at().toString(), touch.human())).toList());
    }

    private List<CreditEngine.Touch> readChain(JSONB value) {
        if (value == null) {
            return List.of();
        }
        List<ChainTouch> rows = json.readValue(
                value.data(), new tools.jackson.core.type.TypeReference<List<ChainTouch>>() {});
        return rows.stream().map(row -> new CreditEngine.Touch(
                row.clickId(), row.linkId(), row.campaignId(), Instant.parse(row.at()), row.human())).toList();
    }

    /** Visible to the job queries in this package, which shape the same row into the aggregate. */
    Conversion toConversionPublic(dev.ngb.attribution_jooq.tables.records.ConversionsRecord row) {
        return toConversion(row);
    }

    private Conversion toConversion(dev.ngb.attribution_jooq.tables.records.ConversionsRecord row) {
        return new Conversion(
                row.getId(),
                new TenantId(row.getTenantId()),
                row.getGoalId(),
                row.getClickId(),
                row.getVisitorId(),
                row.getValue(),
                row.getCurrency(),
                row.getOccurredAt(),
                ConversionSource.valueOf(row.getSource()),
                row.getIdempotencyKey(),
                CreditStatus.valueOf(row.getCreditStatus()),
                row.getOwningCampaignId(),
                readChain(row.getTouchChain()),
                row.getComputedAt(),
                row.getCreatedAt());
    }

    /** The stored chain's shape: the fields a recompute needs and nothing it does not. */
    record ChainTouch(String clickId, String linkId, String campaignId, String at, boolean human) {}
}
