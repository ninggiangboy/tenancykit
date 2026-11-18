package dev.ngb.platform.boot.web;

import dev.ngb.core.spring.web.CoreExceptionHandlers;
import dev.ngb.platform.access.application.permission.exception.UnknownPermissionException;
import dev.ngb.platform.tenancy.application.entitlement.exception.UnknownAppException;
import dev.ngb.platform.tenancy.application.tenant.exception.HostnameAlreadyMappedException;
import dev.ngb.platform.tenancy.application.tenant.exception.TenantCodeAlreadyUsedException;
import dev.ngb.platform.tenancy.application.tenant.exception.TenantNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Platform-specific failures on top of {@link CoreExceptionHandlers}: tenancy and access-catalog errors. */
@RestControllerAdvice
class ApiExceptionHandler extends CoreExceptionHandlers {

    @ExceptionHandler(TenantNotFoundException.class)
    ProblemDetail tenantNotFound(TenantNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler({TenantCodeAlreadyUsedException.class, HostnameAlreadyMappedException.class})
    ProblemDetail tenantConflict(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler({UnknownAppException.class, UnknownPermissionException.class})
    ProblemDetail unprocessable(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, ServletRequestBindingException.class})
    ProblemDetail unreadable(Exception e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The request could not be read.");
    }
}
