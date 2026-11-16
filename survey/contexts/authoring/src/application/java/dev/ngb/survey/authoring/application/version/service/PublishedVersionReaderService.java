package dev.ngb.survey.authoring.application.version.service;

import dev.ngb.survey.authoring.api.version.PublishedVersionReader;
import dev.ngb.survey.authoring.application.version.port.VersionRepository;
import dev.ngb.survey.authoring.domain.version.SurveyVersion;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Serves the published version a respondent's browser reads.
 *
 * <p>It is a service rather than a use case: it reads through the authoring port inside the caller's transaction and
 * changes nothing. A survey that was never published has no current version, so it answers empty rather than failing.
 */
public class PublishedVersionReaderService implements PublishedVersionReader {

    private final VersionRepository versions;

    public PublishedVersionReaderService(VersionRepository versions) {
        this.versions = versions;
    }

    @Override
    public Optional<PublishedVersion> current(UUID surveyId) {
        return versions.latest(surveyId).map(PublishedVersionReaderService::published);
    }

    @Override
    public Optional<PublishedVersion> byNumber(UUID surveyId, int versionNo) {
        return versions.byNumber(surveyId, versionNo).map(PublishedVersionReaderService::published);
    }

    @Override
    public Map<String, String> questionTypes(UUID surveyId) {
        return versions.latest(surveyId).map(version -> typesOf(version.document())).orElse(Map.of());
    }

    /** The question ids and types a version document carries, read without parsing its expressions. */
    private static Map<String, String> typesOf(Map<String, Object> document) {
        Map<String, String> types = new java.util.LinkedHashMap<>();
        Object pages = document.get("pages");
        if (!(pages instanceof List<?> pageList)) {
            return Map.of();
        }
        for (Object rawPage : pageList) {
            if (!(rawPage instanceof Map<?, ?> page) || !(page.get("questions") instanceof List<?> questions)) {
                continue;
            }
            for (Object rawQuestion : questions) {
                if (!(rawQuestion instanceof Map<?, ?> question)) {
                    continue;
                }
                Object id = question.get("id");
                Object type = question.get("type");
                if (id instanceof String text && type instanceof String typeName) {
                    types.put(text, typeName);
                }
            }
        }
        return Map.copyOf(types);
    }

    private static PublishedVersion published(SurveyVersion version) {
        return new PublishedVersion(version.id(), version.versionNo(), version.document());
    }
}
