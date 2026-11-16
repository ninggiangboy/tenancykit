package dev.ngb.survey.insight.application.export.port;

import java.time.Duration;

/**
 * The object store an export's CSV lives in.
 *
 * <p>The worker uploads the file; the API answers a presigned GET with {@code Content-Disposition: attachment}, so a
 * browser downloads it rather than rendering it from the suite's own domain.
 */
public interface ExportStore {

    /** Writes the whole CSV under one key. */
    void put(String objectKey, byte[] content, String contentType);

}
