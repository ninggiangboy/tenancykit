package dev.ngb.survey.authoring.application.version.port;

import dev.ngb.survey.authoring.domain.version.QuestionLineage;
import dev.ngb.survey.authoring.domain.version.SurveyVersion;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The write side of a survey's immutable versions and the lineage a retyping leaves. */
public interface VersionRepository {

    /** Inserts the version and, in the same transaction, the lineage its retyped questions produced. */
    void insert(SurveyVersion version, List<QuestionLineage> lineage, String publishedBy);

    /** The version with the highest number, which is the one a retype is compared against. */
    Optional<SurveyVersion> latest(UUID surveyId);

    /** One version by the number it was published under, which is what a visit pinned at its opening asks for. */
    Optional<SurveyVersion> byNumber(UUID surveyId, int versionNo);
}
