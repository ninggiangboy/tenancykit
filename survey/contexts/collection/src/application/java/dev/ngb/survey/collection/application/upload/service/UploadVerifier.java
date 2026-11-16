package dev.ngb.survey.collection.application.upload.service;

import dev.ngb.survey.authoring.api.flow.AnswerIndexer;
import dev.ngb.survey.collection.application.upload.port.Upload;
import dev.ngb.survey.collection.application.upload.port.UploadRepository;
import dev.ngb.survey.collection.application.upload.port.UploadStore;
import java.util.List;
import java.util.Map;

/**
 * Verifies the objects a submission references before it may complete: each one exists, its length is what was declared,
 * and its first bytes match the type its question allows.
 *
 * <p>A presigned PUT fixes the length and the type into the signature, but a client that knows another S3 endpoint can
 * still have written something else; the bytes are what the server trusts. A file that fails is refused rather than
 * deleted, because the respondent may retry the upload and the sweep removes what never attaches.
 */
public class UploadVerifier {

    private static final int MAGIC_BYTES = 512;

    private final UploadRepository uploads;
    private final UploadStore store;
    private final AnswerIndexer indexer;

    public UploadVerifier(UploadRepository uploads, UploadStore store, AnswerIndexer indexer) {
        this.uploads = uploads;
        this.store = store;
        this.indexer = indexer;
    }

    /**
     * @return why a referenced upload cannot be attached, or empty when every one is what it declared
     */
    public java.util.Optional<String> problem(
            Map<String, Object> document, List<Upload> referenced, String questionId) {
        AnswerIndexer.FileLimit limit = indexer.fileLimit(document, questionId).orElse(null);
        for (Upload upload : referenced) {
            java.util.Optional<Long> size = store.sizeOf(upload.objectKey());
            if (size.isEmpty() || size.get() != upload.declaredSize()) {
                return java.util.Optional.of("size");
            }
            if (limit != null && size.get() > limit.maxBytes()) {
                return java.util.Optional.of("size");
            }
            byte[] head = store.headOf(upload.objectKey(), MAGIC_BYTES).orElse(new byte[0]);
            if (!MagicBytes.matches(upload.declaredMime(), head)) {
                return java.util.Optional.of("type");
            }
        }
        return java.util.Optional.empty();
    }

    /** Attaches every upload a completed response references, so the sweep stops looking at them. */
    public void attach(List<Upload> referenced) {
        referenced.forEach(upload -> uploads.attach(upload.id()));
    }
}
