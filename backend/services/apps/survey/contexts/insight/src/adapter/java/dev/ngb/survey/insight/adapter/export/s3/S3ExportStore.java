package dev.ngb.survey.insight.adapter.export.s3;

import dev.ngb.survey.insight.application.export.port.ExportStore;
import dev.ngb.survey.insight.application.export.query.ExportDownloads;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/**
 * The export's object store: the worker writes the CSV, the API signs a GET.
 *
 * <p>The GET is signed with {@code publicEndpoint}, so the URL names a host a Tenant's browser reaches, and it asks for
 * {@code Content-Disposition: attachment}, so the file downloads rather than rendering from the suite's own domain.
 */
public class S3ExportStore implements ExportStore, ExportDownloads {

    private final S3Client client;
    private final S3Presigner presigner;
    private final String bucket;

    public S3ExportStore(
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
                .forcePathStyle(true)
                .build();
        this.presigner = S3Presigner.builder()
                .endpointOverride(URI.create(publicEndpoint))
                .region(Region.of(region))
                .credentialsProvider(credentials)
                .build();
    }

    @Override
    public void put(String objectKey, byte[] content, String contentType) {
        client.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(objectKey)
                        .contentType(contentType)
                        .build(),
                software.amazon.awssdk.core.sync.RequestBody.fromBytes(content));
    }

    @Override
    public Optional<String> presignGet(String objectKey, String filename, Duration lifetime) {
        try {
            GetObjectRequest get = GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(objectKey)
                    .responseContentDisposition("attachment; filename=\"" + safeFilename(filename) + "\"")
                    .build();
            return Optional.of(presigner.presignGetObject(GetObjectPresignRequest.builder()
                            .signatureDuration(lifetime)
                            .getObjectRequest(get)
                            .build())
                    .url()
                    .toString());
        } catch (S3Exception failure) {
            return Optional.empty();
        }
    }


    /** A filename cannot carry a quote or a path separator into the header, whatever the caller passed. */
    private static String safeFilename(String filename) {
        String name = filename == null || filename.isBlank() ? "export.csv" : filename;
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
