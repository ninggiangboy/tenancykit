package dev.ngb.customer.boot.web;

import dev.ngb.core.spring.web.CoreExceptionHandlers;
import dev.ngb.customer.activity.application.activity.exception.ActivityProblem;
import dev.ngb.customer.customers.application.consent.exception.ConsentProblem;
import dev.ngb.customer.customers.application.customer.exception.CustomerErasedException;
import dev.ngb.customer.customers.application.customer.exception.CustomerProblem;
import dev.ngb.customer.customers.application.customer.exception.ExportInProgressException;
import dev.ngb.customer.customers.application.customer.exception.IfMatchRequiredException;
import dev.ngb.customer.customers.application.customer.exception.InvalidBatchException;
import dev.ngb.customer.customers.application.customer.exception.InvalidCustomerException;
import dev.ngb.customer.customers.application.field.exception.FieldProblem;
import dev.ngb.customer.orders.application.order.exception.InvalidOrderBatchException;
import dev.ngb.customer.orders.application.order.exception.OrderProblem;
import dev.ngb.customer.webhook.application.endpoint.exception.PreconditionRequiredException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * customer's problem bodies on top of {@link CoreExceptionHandlers}: the suite's one error shape, plus a readable answer for
 * a body Jackson cannot read.
 *
 * <p>The rule a feature's exception is validated against is {@link IllegalArgumentException}: an endpoint's own
 * request rules — an unknown event type, a malformed tag, a limit exceeded — answer {@code 400} with the rule's own
 * message, which is written for the caller and carries no personal data.
 *
 * <p>A customer refusal carries its own code, so a caller branches on {@code VALIDATION_FAILED} or
 * {@code CUSTOMER_ERASED} rather than on the wording of a message, and a batch item reports the same code.
 */
@RestControllerAdvice
class ApiExceptionHandler extends CoreExceptionHandlers {

    /** A conditional change with no If-Match: the caller must say which version it is changing. */
    @ExceptionHandler(PreconditionRequiredException.class)
    ProblemDetail preconditionRequired(PreconditionRequiredException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.PRECONDITION_REQUIRED, e.getMessage());
    }

    @ExceptionHandler(IfMatchRequiredException.class)
    ProblemDetail ifMatchRequired(IfMatchRequiredException e) {
        return problem(HttpStatus.PRECONDITION_REQUIRED, e);
    }

    /** The body is not a customer: an attribute is empty, too long or not an address. */
    @ExceptionHandler(InvalidCustomerException.class)
    ProblemDetail invalidCustomer(InvalidCustomerException e) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, e);
    }

    /** The customer is an anonymous marker after an erasure and holds nothing a caller may rewrite. */
    @ExceptionHandler(CustomerErasedException.class)
    ProblemDetail customerErased(CustomerErasedException e) {
        return problem(HttpStatus.CONFLICT, e);
    }

    /** The batch envelope is wrong: it carries no items, or more than a call may hold. */
    @ExceptionHandler(InvalidBatchException.class)
    ProblemDetail invalidBatch(InvalidBatchException e) {
        return problem(HttpStatus.BAD_REQUEST, e);
    }

    /** An order or return batch's envelope is wrong: no orders, or more orders or lines than one call may carry. */
    @ExceptionHandler(InvalidOrderBatchException.class)
    ProblemDetail invalidOrderBatch(InvalidOrderBatchException e) {
        return problem(HttpStatus.BAD_REQUEST, e);
    }

    /**
     * An order refusal: a missing order is 404, an erased customer's reference is 409, and every other refusal — an
     * unknown customer, more lines than a call may carry, a cumulative return above the sale — is 422.
     */
    @ExceptionHandler(OrderProblem.class)
    ProblemDetail orderProblem(OrderProblem e) {
        return problem(orderStatusOf(e.code()), e);
    }

    /** A field or consent refusal: a missing definition is 404, a taken key or an immutable type 409, the rest 422. */
    @ExceptionHandler({FieldProblem.class, ConsentProblem.class})
    ProblemDetail definitionProblem(CustomerProblem e) {
        return problem(statusOf(e.code()), e);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail unreadableBody(HttpMessageNotReadableException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The request body is not valid.");
    }

    /**
     * An activity refusal: an unknown customer or a payload over its bound is {@code 422}, an erased customer's
     * reference and a reused reference with another body are {@code 409}, and a malformed batch envelope is
     * {@code 400}.
     */
    @ExceptionHandler(ActivityProblem.class)
    ProblemDetail activityProblem(ActivityProblem e) {
        return problem(activityStatusOf(e.code()), e.code(), e.getMessage());
    }

    /** Another export of this tenant is still running: the caller waits for it rather than reading the rows twice. */
    @ExceptionHandler(ExportInProgressException.class)
    ProblemDetail exportInProgress(ExportInProgressException e) {
        return problem(HttpStatus.TOO_MANY_REQUESTS, e.code(), e.getMessage());
    }

    /** The status a refusal code answers with, so a caller branches on the code rather than on the wording. */
    private static HttpStatus statusOf(String code) {
        if (code.endsWith("_NOT_FOUND")) {
            return HttpStatus.NOT_FOUND;
        }
        if (code.endsWith("_EXISTS") || code.endsWith("_IMMUTABLE")) {
            return HttpStatus.CONFLICT;
        }
        return HttpStatus.UNPROCESSABLE_CONTENT;
    }

    /** An order refusal's status: a missing order or customer is 404, an erased customer is 409, the rest is 422. */
    private static HttpStatus orderStatusOf(String code) {
        if (code.endsWith("_NOT_FOUND")) {
            return HttpStatus.NOT_FOUND;
        }
        if (code.equals("CUSTOMER_ERASED")) {
            return HttpStatus.CONFLICT;
        }
        return HttpStatus.UNPROCESSABLE_CONTENT;
    }

    /** An activity refusal's status: an erased customer is 409, a malformed batch envelope is 400, the rest is 422. */
    private static HttpStatus activityStatusOf(String code) {
        if (code.equals("CUSTOMER_ERASED") || code.equals("IDEMPOTENCY_CONFLICT")) {
            return HttpStatus.CONFLICT;
        }
        if (code.equals("INVALID_REQUEST") || code.equals("BATCH_LIMIT_EXCEEDED")) {
            return HttpStatus.BAD_REQUEST;
        }
        return HttpStatus.UNPROCESSABLE_CONTENT;
    }

    private static ProblemDetail problem(HttpStatus status, CustomerProblem e) {
        return problem(status, e.code(), e.getMessage());
    }

    private static ProblemDetail problem(HttpStatus status, OrderProblem e) {
        return problem(status, e.code(), e.getMessage());
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        return problem;
    }
}
