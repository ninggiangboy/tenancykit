package dev.ngb.mailer.email.application.email.port;

import java.util.Objects;

/**
 * What a transport reported. The three cases are the only three decisions the ledger makes, and they are deliberately
 * not an HTTP status or an SDK exception: {@code Rejected} is terminal at once, {@code Transient} is retried with
 * backoff, and {@code Accepted} completes the delivery.
 */
public sealed interface SendOutcome permits SendOutcome.Accepted, SendOutcome.Rejected, SendOutcome.Transient {

    /**
     * The provider took the message. Under SES this is a 200, which means accepted — not delivered.
     *
     * @param messageId the provider's identifier, stored on the ledger so an operator can ask the provider about it
     */
    record Accepted(String messageId) implements SendOutcome {
        public Accepted {
            Objects.requireNonNull(messageId, "messageId");
        }
    }

    /** The request itself is wrong: a bad address, a revoked credential, a paused account. Retrying cannot help. */
    record Rejected(String reason) implements SendOutcome {
        public Rejected {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** A condition that may pass: throttling, an outage, a timeout. Retried until the policy runs out. */
    record Transient(String reason) implements SendOutcome {
        public Transient {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
