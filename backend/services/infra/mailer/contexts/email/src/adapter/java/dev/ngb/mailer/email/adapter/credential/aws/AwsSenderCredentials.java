package dev.ngb.mailer.email.adapter.credential.aws;

import dev.ngb.mailer.email.application.email.port.SenderCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;

/**
 * The AWS side of the opaque credential handle. It exists so the application layer can pass credentials around without
 * naming an SDK type: only this package, and the SES adapter beside it, ever unwraps it.
 */
public record AwsSenderCredentials(AwsCredentialsProvider provider) implements SenderCredentials {

    public AwsSenderCredentials {
        if (provider == null) {
            throw new IllegalArgumentException("a credentials provider is required");
        }
    }
}
