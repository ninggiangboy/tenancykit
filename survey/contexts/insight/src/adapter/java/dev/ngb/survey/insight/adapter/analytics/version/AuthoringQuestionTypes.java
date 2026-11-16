package dev.ngb.survey.insight.adapter.analytics.version;

import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.survey.authoring.api.version.PublishedVersionReader;
import dev.ngb.survey.insight.application.analytics.query.QuestionTypes;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Reads a survey's question types through authoring's api package, inside the caller's tenant scope.
 *
 * <p>It is an adapter because the lookup is a read of authoring's data: insight may not touch the {@code surveys} or
 * {@code survey_versions} tables, so every fact it needs about a version comes through this seam.
 */
public class AuthoringQuestionTypes implements QuestionTypes {

    private final UnitOfWork unitOfWork;
    private final PublishedVersionReader versions;

    public AuthoringQuestionTypes(UnitOfWork unitOfWork, PublishedVersionReader versions) {
        this.unitOfWork = unitOfWork;
        this.versions = versions;
    }

    @Override
    public Map<UUID, String> of(UUID surveyId) {
        Map<String, String> types =
                unitOfWork.transactional(() -> versions.questionTypes(surveyId));
        Map<UUID, String> byId = new LinkedHashMap<>();
        types.forEach((id, type) -> {
            try {
                byId.put(UUID.fromString(id), type);
            } catch (RuntimeException notAnId) {
                // A malformed id cannot be a question a typed row refers to, so it is skipped rather than failing.
            }
        });
        return Map.copyOf(byId);
    }
}
