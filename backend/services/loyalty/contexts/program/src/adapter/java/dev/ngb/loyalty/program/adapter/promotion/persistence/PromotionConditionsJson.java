package dev.ngb.loyalty.program.adapter.promotion.persistence;

import dev.ngb.loyalty.program.domain.promotion.PromotionConditions;
import java.time.LocalTime;
import java.util.List;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The stored `conditions` document: the closed set of condition kinds, written as one jsonb object. Empty kinds are left
 * out, so a promotion with no restriction stores an empty document, and a field this version does not know is ignored
 * rather than failing a read.
 */
public class PromotionConditionsJson {

    /** The document as it is stored; a missing field reads as no restriction. */
    record Document(
            List<String> categories,
            List<String> skus,
            List<String> storeCodes,
            List<Integer> daysOfWeek,
            List<String> tierCodes,
            String timeFrom,
            String timeTo) {}

    private final JsonMapper json;

    public PromotionConditionsJson(JsonMapper json) {
        this.json = json;
    }

    public String write(PromotionConditions conditions) {
        return json.writeValueAsString(new Document(
                emptyToNull(conditions.categories()),
                emptyToNull(conditions.skus()),
                emptyToNull(conditions.storeCodes()),
                conditions.daysOfWeek().isEmpty() ? null : conditions.daysOfWeek(),
                emptyToNull(conditions.tierCodes()),
                conditions.timeFrom() == null ? null : conditions.timeFrom().toString(),
                conditions.timeTo() == null ? null : conditions.timeTo().toString()));
    }

    public PromotionConditions read(String document) {
        if (document == null || document.isBlank()) {
            return PromotionConditions.NONE;
        }
        Document stored = json.readerFor(Document.class)
                .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .readValue(document);
        return new PromotionConditions(
                stored.categories(),
                stored.skus(),
                stored.storeCodes(),
                stored.daysOfWeek(),
                stored.tierCodes(),
                time(stored.timeFrom()),
                time(stored.timeTo()));
    }

    private static LocalTime time(String raw) {
        return raw == null || raw.isBlank() ? null : LocalTime.parse(raw);
    }

    private static List<String> emptyToNull(List<String> values) {
        return values == null || values.isEmpty() ? null : values;
    }
}
