package dev.ngb.notification.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.core.id.TenantId;
import dev.ngb.notification.dispatch.adapter.notification.email.ContractEmailCommandFactory;
import dev.ngb.notification.dispatch.adapter.notification.email.EmailCommandSchemaValidator;
import dev.ngb.notification.dispatch.application.notification.exception.InvalidEmailCommandException;
import dev.ngb.notification.dispatch.application.notification.port.EmailCommandFactory.EmailCommandRequest;
import dev.ngb.notification.dispatch.domain.notification.NotificationType;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The producer validates its own command against the packaged contract, so a drift fails here, not in mailer. */
class EmailCommandContractTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final EmailCommandSchemaValidator validator = new EmailCommandSchemaValidator(json);
    private final ContractEmailCommandFactory factory = new ContractEmailCommandFactory(validator);

    @Test
    void everyTypeBuildsAValidCommand() {
        for (NotificationType type : NotificationType.values()) {
            var command = factory.create(request("Acme", "a@example.com", "Subject for " + type.name()));
            assertThat(command.payload())
                    .containsKeys("requestId", "to", "fromKey", "subject", "html", "text")
                    .doesNotContainKey("cc");
        }
    }

    @Test
    void aMissingRequiredFieldIsRefused() {
        Map<String, Object> data = Map.of(
                "requestId", UUID.randomUUID().toString(),
                "fromKey", "noreply",
                "subject", "Hi");
        assertThatThrownBy(() -> validator.validate(data)).isInstanceOf(InvalidEmailCommandException.class);
    }

    @Test
    void aNewlineInTheSubjectIsRefused() {
        assertThatThrownBy(() -> factory.create(request("Acme", "a@example.com", "Header\nInjected")))
                .isInstanceOf(InvalidEmailCommandException.class);
    }

    @Test
    void aBlankRecipientIsRefused() {
        assertThatThrownBy(() -> factory.create(request("Acme", "", "Hi")))
                .isInstanceOf(InvalidEmailCommandException.class);
    }

    /** A name of only forbidden characters omits the optional key rather than sending an empty string. */
    @Test
    void aTenantNameWithNothingLeftIsOmitted() {
        var command = factory.create(request("\"<>,,\n", "a@example.com", "Hi"));
        assertThat(command.payload()).doesNotContainKey("fromName");
    }

    @Test
    void requestIdIsTheDispatchId() {
        UUID dispatchId = UUID.randomUUID();
        var command = factory.create(new EmailCommandRequest(
                dispatchId, TenantId.of("00000000-0000-7000-8000-000000000042"), "Acme", "a@example.com",
                "noreply", "Hi", "<p>Hi</p>", "Hi"));
        assertThat(command.payload()).containsEntry("requestId", dispatchId.toString());
        assertThat(command.aggregate()).isEqualTo("notification/" + dispatchId);
    }

    private static EmailCommandRequest request(String tenantName, String recipient, String subject) {
        return new EmailCommandRequest(
                UUID.randomUUID(),
                TenantId.of("00000000-0000-7000-8000-000000000042"),
                tenantName,
                recipient,
                "noreply",
                subject,
                "<p>Hello</p>",
                "Hello");
    }
}
