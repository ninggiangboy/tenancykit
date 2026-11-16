package dev.ngb.survey.insight.application.export.query;

import java.time.Duration;
import java.util.Optional;

/**
 * Signs the download a finished export answers with.
 *
 * <p>It is a query-side port because it is read behaviour: the write side uploads the file and never signs a GET, so a
 * query does not reach through a write port to produce a link.
 */
public interface ExportDownloads {

    /**
     * Signs a GET for one object.
     *
     * @param lifetime how long the URL stays valid
     * @return the URL, or empty when the object is gone
     */
    Optional<String> presignGet(String objectKey, String filename, Duration lifetime);
}
