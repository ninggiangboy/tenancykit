package dev.ngb.mailroom.sending.adapter.email.messaging;

import dev.ngb.mailroom.sending.application.email.port.EmailCommandFactory;
import dev.ngb.mailroom.sending.domain.email.Email;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the command and validates it against the packaged contract before the outbox row exists.
 *
 * <p>{@code fromName} is tenant-controlled text and the contract refuses quotes, angle brackets, commas and line
 * breaks. It is sanitised here and omitted entirely when nothing survives: {@code fromName} is optional, while an
 * empty string fails {@code minLength: 1} and would roll back the send.
 */
public class ContractEmailCommandFactory implements EmailCommandFactory {

    private static final int FROM_NAME_LIMIT = 128;

    private final EmailCommandSchemaValidator validator;

    public ContractEmailCommandFactory(EmailCommandSchemaValidator validator) {
        this.validator = validator;
    }

    @Override
    public EmailCommand create(Email email, Map<String, String> headers) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requestId", email.requestId());
        data.put("to", List.of(email.toAddress()));
        data.put("fromKey", email.fromKey());
        String fromName = sanitise(email.fromName());
        if (!fromName.isEmpty()) {
            data.put("fromName", fromName);
        }
        if (email.replyTo() != null && !email.replyTo().isBlank()) {
            data.put("replyTo", email.replyTo());
        }
        data.put("subject", email.subject());
        if (email.bodyHtml() != null) {
            data.put("html", email.bodyHtml());
        }
        if (email.bodyText() != null) {
            data.put("text", email.bodyText());
        }
        if (headers != null && !headers.isEmpty()) {
            data.put("headers", Map.copyOf(headers));
        }
        validator.validate(data);
        return new EmailCommand("email/" + email.id(), data);
    }

    static String sanitise(String name) {
        if (name == null) {
            return "";
        }
        String cleaned = name.replace("\"", "")
                .replace("<", "")
                .replace(">", "")
                .replace(",", "")
                .replace("\r", "")
                .replace("\n", "")
                .trim();
        return cleaned.length() > FROM_NAME_LIMIT ? cleaned.substring(0, FROM_NAME_LIMIT).trim() : cleaned;
    }
}
