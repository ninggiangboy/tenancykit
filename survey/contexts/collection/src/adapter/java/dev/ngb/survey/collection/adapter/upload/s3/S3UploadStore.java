package dev.ngb.survey.collection.adapter.upload.s3;

import dev.ngb.survey.collection.application.upload.port.UploadStore;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * MinIO or S3 over the AWS SDK v2, the same major the mailer uses for SES.
 *
 * <p>Two endpoints: the client signs against the address the server reaches, while the presigner signs with
 * {@code publicEndpoint}, so the URL a browser receives names a host the browser can actually resolve. Signing with
 * the internal endpoint would produce a URL that works from inside the cluster and nowhere else.
 *
 * <p>The length and type are fixed into the signature, so an object cannot be uploaded larger than the size the server
 * checked, nor with a type other than the one it validated.
 */
public class S3UploadStore implements UploadStore {

    private final S3Client client;
    private final S3Presigner presigner;
    private final String bucket;

    public S3UploadStore(
            String endpoint,
            String publicEndpoint,
            String bucket,
            String region,
            String accessKey,
            String secretKey) {
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(accessKey, secretKey));
        this.bucket = bucket;
        this.client = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(credentials)
                // MinIO serves a path-style address, which is what the compose deployment runs.
                .forcePathStyle(true)
                .build();
        this.presigner = S3Presigner.builder()
                .endpointOverride(URI.create(publicEndpoint))
                .region(Region.of(region))
                .credentialsProvider(credentials)
                .build();
    }

    @Override
    public PresignedPut presignPut(String objectKey, long size, String mime, Duration lifetime) {
        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .contentLength(size)
                .contentType(mime)
                .build();
        PresignedPutObjectRequest signed = presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(lifetime)
                .putObjectRequest(put)
                .build());
        return new PresignedPut(signed.url().toString(), Map.of("Content-Type", mime));
    }

    @Override
    public Optional<Long> sizeOf(String objectKey) {
        try {
            return Optional.of(client.headObject(
                            HeadObjectRequest.builder().bucket(bucket).key(objectKey).build())
                    .contentLength());
        } catch (NoSuchKeyException absent) {
            return Optional.empty();
        } catch (S3Exception failure) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<byte[]> headOf(String objectKey, int bytes) {
        try {
            GetObjectRequest request = GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(objectKey)
                    .range("bytes=0-" + (bytes - 1))
                    .build();
            ResponseBytes<software.amazon.awssdk.services.s3.model.GetObjectResponse> response =
                    client.getObjectAsBytes(request);
            return Optional.of(response.asByteArray());
        } catch (S3Exception failure) {
            return Optional.empty();
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(objectKey).build());
        } catch (S3Exception alreadyGone) {
            // The sweep runs more than once; a missing object is the state it wanted.
        }
    }
}
