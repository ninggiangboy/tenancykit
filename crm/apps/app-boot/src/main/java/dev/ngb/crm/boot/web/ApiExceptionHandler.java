package dev.ngb.crm.boot.web;

import dev.ngb.core.spring.web.CoreExceptionHandlers;
import dev.ngb.crm.customers.application.consent.exception.ConsentProblem;
import dev.ngb.crm.customers.application.customer.exception.CustomerErasedException;
import dev.ngb.crm.customers.application.customer.exception.CustomerProblem;
import dev.ngb.crm.customers.application.customer.exception.IfMatchRequiredException;
import dev.ngb.crm.customers.application.customer.exception.InvalidBatchException;
import dev.ngb.crm.customers.application.customer.exception.InvalidCustomerException;
import dev.ngb.crm.customers.application.field.exception.FieldProblem;
import dev.ngb.crm.webhook.application.endpoint.exception.PreconditionRequiredException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * crm's problem bodies on top of {@link CoreExceptionHandlers}: the suite's one error shape, plus a readable answer for
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

    /** A field or consent refusal: a missing definition is 404, a taken key or an immutable type 409, the rest 422. */
    @ExceptionHandler({FieldProblem.class, ConsentProblem.class})
    ProblemDetail definitionProblem(CustomerProblem e) {
        return problem(statusOf(e.code()), e);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail unreadableBody(HttpMessageNotReadableException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The request body is not valid.");
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

    private static ProblemDetail problem(HttpStatus status, CustomerProblem e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        problem.setProperty("code", e.code());
        return problem;
    }
}
