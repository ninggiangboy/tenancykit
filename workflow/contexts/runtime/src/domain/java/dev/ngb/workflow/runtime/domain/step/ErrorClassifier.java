package dev.ngb.workflow.runtime.domain.step;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ErrorClassifier {

    private static final int STATUS_TIMEOUT = 408;
    private static final int STATUS_TOO_MANY_REQUESTS = 429;
    private static final int STATUS_SERVER_ERROR = 500;

    private ErrorClassifier() {}

    public static ErrorClass classify(StepOutcome outcome) {
        return outcome.failed() ? classify(outcome.failure(), outcome.response()) : null;
    }

    public static ErrorClass classify(String failure, Object response) {
        String reason = failure == null ? "" : failure;
        if (reason.contains("credential_invalid") || reason.startsWith("limit_exceeded:")) {
            return ErrorClass.FATAL;
        }
        Integer status = status(response);
        if (status != null && status > 0) {
            return status == STATUS_TIMEOUT || status == STATUS_TOO_MANY_REQUESTS || status >= STATUS_SERVER_ERROR
                    ? ErrorClass.RETRYABLE
                    : ErrorClass.FATAL;
        }
        return reason.startsWith("http_call_failed:") ? ErrorClass.RETRYABLE : ErrorClass.FATAL;
    }

    public static Map<String, Object> envelope(ErrorClass errorClass, String failure, Object response) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("class", errorClass.name());
        error.put("message", failure);
        error.put("status", status(response));
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("error", error);
        return envelope;
    }

    private static Integer status(Object response) {
        return response instanceof Map<?, ?> members && members.get("status") instanceof Number number
                ? number.intValue()
                : null;
    }
}
