package dev.ngb.workflow.webhook.application.delivery.port;

public interface PayloadSigner {

    /** @return the {@code Workflow-Signature} header value for the body signed at {@code timestampSeconds} */
    String sign(String secret, long timestampSeconds, byte[] body);
}
