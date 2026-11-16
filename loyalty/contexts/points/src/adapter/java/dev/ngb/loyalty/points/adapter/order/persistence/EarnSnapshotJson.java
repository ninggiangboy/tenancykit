package dev.ngb.loyalty.points.adapter.order.persistence;

import dev.ngb.loyalty.points.domain.earn.EarnSnapshot;
import dev.ngb.loyalty.points.domain.earn.SnapshotLine;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The stored `snapshot` document: the rules an order's earn used, written as one jsonb object.
 *
 * <p>The shape is the contract's own, {@code {unitAmount, currency, tierCode, tierMultiplier, lines:[{lineNo,
 * multiplier, promotionIds}]}}, so a reader of the order can see why a line was worth what it was. A field this version
 * does not know is ignored rather than failing a read of an old order.
 */
public class EarnSnapshotJson {

    /** The document as it is stored. */
    record Document(Long unitAmount, String currency, String tierCode, BigDecimal tierMultiplier, List<Line> lines) {

        record Line(Integer lineNo, BigDecimal multiplier, List<String> promotionIds) {}
    }

    private final JsonMapper json;

    public EarnSnapshotJson(JsonMapper json) {
        this.json = json;
    }

    public String write(EarnSnapshot snapshot) {
        return json.writeValueAsString(new Document(
                snapshot.unitAmount(),
                snapshot.currency(),
                snapshot.tierCode(),
                snapshot.tierMultiplier(),
                snapshot.lines().stream()
                        .map(line -> new Document.Line(
                                line.lineNo(),
                                line.multiplier(),
                                line.promotionIds().stream().map(UUID::toString).toList()))
                        .toList()));
    }

    public EarnSnapshot read(String document) {
        Document stored = json.readerFor(Document.class)
                .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .readValue(document);
        return new EarnSnapshot(
                stored.unitAmount(),
                stored.currency(),
                stored.tierCode(),
                stored.tierMultiplier(),
                stored.lines() == null
                        ? List.of()
                        : stored.lines().stream()
                                .map(line -> new SnapshotLine(
                                        line.lineNo(),
                                        line.multiplier(),
                                        line.promotionIds() == null
                                                ? List.of()
                                                : line.promotionIds().stream()
                                                        .map(UUID::fromString)
                                                        .toList()))
                                .toList());
    }
}
