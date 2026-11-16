package dev.ngb.survey.collection.application.upload.port;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * The object store a file answer's bytes live in.
 *
 * <p>Two endpoints matter and they are not the same host: the server signs a PUT against the endpoint it can reach,
 * while the URL a browser receives must name a host the browser reaches. That split is why the presigner is
 * configured with both.
 */
public interface UploadStore {

    /**
     * Signs a PUT for one object.
     *
     * @param objectKey the key the server derived, never a filename
     * @param size the exact length the upload must have, fixed so the object cannot grow after the check
     * @param mime the type the upload must declare
     * @param lifetime how long the URL stays valid
     * @return the URL and the headers the browser must send with the body
     */
    PresignedPut presignPut(String objectKey, long size, String mime, Duration lifetime);

    /** The object's stored length, or empty when nothing was uploaded under this key. */
    java.util.Optional<Long> sizeOf(String objectKey);

    /** The object's first {@code bytes} bytes, which is what a magic-byte check reads. */
    java.util.Optional<byte[]> headOf(String objectKey, int bytes);

    /** Deletes an object; a missing one is not an error, because the sweep runs more than once. */
    void delete(String objectKey);

    /** A presigned PUT and the headers it requires. */
    record PresignedPut(String url, Map<String, String> headers) {}
}
