package dev.ngb.survey.authoring.domain.version;

import dev.ngb.core.id.TenantId;
import java.util.Objects;
import java.util.UUID;

/**
 * The record a retyped question leaves: the new id, the id it replaced and the first version that carries it.
 *
 * <p>Ids are otherwise stable across versions. This row is what lets an aggregate show a question whose type changed
 * as its own series while still pointing at the series it continues.
 */
public record QuestionLineage(
        UUID questionId, TenantId tenantId, UUID surveyId, UUID replaces, int firstVersionNo) {

    public QuestionLineage {
        Objects.requireNonNull(questionId, "questionId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(surveyId, "surveyId");
        Objects.requireNonNull(replaces, "replaces");
        if (questionId.equals(replaces)) {
            throw new IllegalArgumentException("a replacement cannot replace itself");
        }
        if (firstVersionNo < 1) {
            throw new IllegalArgumentException("a version is numbered from 1");
        }
    }
}
