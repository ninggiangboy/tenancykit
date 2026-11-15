package dev.ngb.notification.dispatch.adapter.notification.email;

import dev.ngb.notification.dispatch.application.notification.port.EmailCommandFactory;
import dev.ngb.notification.dispatch.application.notification.port.NotificationEmailCommand;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the command and validates it against the packaged contract before the outbox row exists.
 *
 * <p>{@code fromName} is tenant-controlled text and the contract refuses quotes, angle brackets, commas and line
 * breaks. It is sanitised here and <b>omitted entirely when nothing survives</b>: {@code fromName} is optional, while
 * an empty string fails {@code minLength: 1} and would roll back the dispatch, retry and dead-letter that tenant's
 * whole stream.
 */
public class ContractEmailCommandFactory implements EmailCommandFactory {

    private static final int FROM_NAME_LIMIT = 128;

    private final EmailCommandSchemaValidator validator;

    public ContractEmailCommandFactory(EmailCommandSchemaValidator validator) {
        this.validator = validator;
    }

    @Override
    public NotificationEmailCommand create(EmailCommandRequest request) {
        Map<String, Object> data = new LinkedHashMap<>();
        // The dispatch id is both the request id and the outbox event id, so every retry resolves to one mailer ledger.
        data.put("requestId", request.dispatchId().toString());
        data.put("to", List.of(request.recipient()));
        data.put("fromKey", request.fromKey());
        String fromName = sanitise(request.tenantName());
        if (!fromName.isEmpty()) {
            data.put("fromName", fromName);
        }
        data.put("subject", request.subject());
        data.put("html", request.html());
        data.put("text", request.text());
        validator.validate(data);
        return new NotificationEmailCommand(
                request.dispatchId(), "notification/" + request.dispatchId(), data);
    }

    /** Removes what would break the display-name position and truncates; an empty result means "omit the key". */
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
